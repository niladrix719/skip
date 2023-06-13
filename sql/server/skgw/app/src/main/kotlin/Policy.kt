package io.skiplabs.skgw

import java.io.BufferedWriter
import java.lang.ref.WeakReference
import java.time.Duration
import java.time.Instant
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

// this factors out policy and monitoring from mechanism
interface ServerPolicy {
  fun shouldAcceptConnection(db: String): Boolean

  // you can then hook in to socket events by using socket.observeLifecycle
  fun notifySocketCreated(socket: MuxedSocket, db: String)

  // short-circuits message receive if returns false. the message is
  // just dropped. may manipulate the stream - e.g. send/close/error
  // it.
  fun shouldDeliverMessage(request: ProtoMessage, stream: OrchestrationStream, db: String): Boolean

  // short-circuits message sending if returns false. the message is
  // just dropped. may manipulate the stream - e.g. send/close/error.
  fun shouldEmitMessage(msg: ProtoMessage, stream: OrchestrationStream, db: String): Boolean
}

// no policy. useful for subclasses that want to define partial policy
// -- template method pattern -- or as the default in composition
open class NullServerPolicy : ServerPolicy {
  override fun shouldAcceptConnection(db: String): Boolean {
    return true
  }

  override fun notifySocketCreated(socket: MuxedSocket, db: String) {}

  override fun shouldDeliverMessage(
      request: ProtoMessage,
      stream: OrchestrationStream,
      db: String
  ): Boolean {
    return true
  }

  override fun shouldEmitMessage(
      msg: ProtoMessage,
      stream: OrchestrationStream,
      db: String
  ): Boolean {
    return true
  }
}

class LimitGlobalConnections(val maxConns: Config.Value<Int>) : NullServerPolicy() {

  val n: AtomicInteger = AtomicInteger(0)

  override fun shouldAcceptConnection(db: String): Boolean {
    val n = n.get()
    // params are null: doesn't make sense to specialise this
    val limit = maxConns.get(accessKey = null, db = null)
    val acceptable = n < limit
    if (!acceptable) {
      System.err.println("Rejecting conn as ${n} >= ${limit}")
    }
    return acceptable
  }

  override fun notifySocketCreated(socket: MuxedSocket, db: String) {
    n.incrementAndGet()
    socket.observeLifecycle { state ->
      when (state) {
        MuxedSocket.State.CLOSED -> n.decrementAndGet()
        MuxedSocket.State.IDLE,
        MuxedSocket.State.AUTH_RECV,
        MuxedSocket.State.CLOSING,
        MuxedSocket.State.CLOSE_WAIT -> Unit
      }
    }
  }
}

class LimitConnectionsPerDb(val maxConnsPerDatabase: UInt) : NullServerPolicy() {

  val openConns: ConcurrentMap<String, Int> = ConcurrentHashMap()

  override fun notifySocketCreated(socket: MuxedSocket, db: String) {
    val n = openConns.merge(db, 1) { oldvalue, _ -> oldvalue + 1 } ?: 1

    socket.observeLifecycle { state ->
      when (state) {
        MuxedSocket.State.CLOSED -> openConns.merge(db, 0) { oldvalue, _ -> oldvalue - 1 }
        MuxedSocket.State.IDLE,
        MuxedSocket.State.AUTH_RECV,
        MuxedSocket.State.CLOSING,
        MuxedSocket.State.CLOSE_WAIT -> Unit
      }
    }

    if (n > maxConnsPerDatabase.toInt()) {
      socket.errorSocket(2000u, "Database connection limit reached")
    }
  }
}

class LimitConnectionsPerUser(val maxConnsPerUser: UInt) : NullServerPolicy() {

  data class DbUser(val user: String, val db: String)

  val openConns: ConcurrentMap<DbUser, Int> = ConcurrentHashMap()

  override fun notifySocketCreated(socket: MuxedSocket, db: String) {
    socket.observeLifecycle { state ->
      val user = socket.authenticatedWith?.msg?.accessKey

      if (user != null) {
        val key = DbUser(user, db)
        when (state) {
          MuxedSocket.State.CLOSED -> {
            openConns.merge(key, 0) { oldvalue, _ ->
              val n = oldvalue - 1
              if (n < 1) null else n
            }
          }
          MuxedSocket.State.AUTH_RECV -> {
            val n = openConns.merge(key, 1) { oldvalue, _ -> oldvalue + 1 }
            if (n != null && n > maxConnsPerUser.toInt()) {
              socket.errorSocket(2000u, "User connection limit reached")
            }
          }
          MuxedSocket.State.IDLE,
          MuxedSocket.State.CLOSING,
          MuxedSocket.State.CLOSE_WAIT -> Unit
        }
      }
    }
  }
}

class TokenBucket(val arrivalRatePerSecond: Double, val size: UInt) {
  private var mutex: Lock = ReentrantLock()
  private var tokens: UInt = size
  private var lastArrival: Instant = Instant.now()

  // null if can immediately service otherwise a non-zero duration
  // estimate of how long to wait before the request should be
  // servicable - be careful, this could easily lead to unfairness
  // without a queue.
  fun requestTokens(n: UInt): Duration? {
    mutex.lock()
    try {
      val now = Instant.now()
      val deltaMillis = Duration.between(lastArrival, now).toMillis()
      val oldTokens = tokens
      tokens = Math.floor(tokens.toLong() + (deltaMillis * arrivalRatePerSecond / 1000)).toUInt()
      if (tokens > oldTokens) {
        // this condition protects the floor from starving updates
        // that arrive fast enough. it ensures progress
        lastArrival = now
      }
      // clip to prevent accumulating enough tokens to allow unacceptable spikes
      tokens = Math.min(tokens.toLong(), size.toLong()).toUInt()

      if (n <= tokens) {
        tokens = tokens - n
        return null
      }

      return Duration.ofMillis(((n - tokens).toLong() / arrivalRatePerSecond * 1000).toLong())
    } finally {
      mutex.unlock()
    }
  }
}

class LeakyBucket(val drainRatePerSecond: Double) {
  private var level: AtomicInteger = AtomicInteger(0)

  // add n to the bucket. return how long it will take to drain
  fun fill(n: Int): Duration {
    val l = level.addAndGet(n)
    val tMillis = l / drainRatePerSecond * 1000
    return Duration.ofMillis(tMillis.toLong()) // truncate, we don't need too much resolution
  }
}

class RateLimitRequestsPerConnection(val maxQpsPerConn: UInt, val maxSpikePerConn: UInt) :
    NullServerPolicy() {

  val openConns: ConcurrentMap<MuxedSocket, TokenBucket> = ConcurrentHashMap()

  override fun notifySocketCreated(socket: MuxedSocket, db: String) {
    openConns.put(socket, TokenBucket(maxQpsPerConn.toDouble(), maxSpikePerConn))
    socket.observeLifecycle { state ->
      when (state) {
        MuxedSocket.State.IDLE,
        MuxedSocket.State.AUTH_RECV,
        MuxedSocket.State.CLOSING,
        MuxedSocket.State.CLOSE_WAIT -> Unit
        MuxedSocket.State.CLOSED -> {
          openConns.remove(socket)
        }
      }
    }
  }

  override fun shouldDeliverMessage(
      request: ProtoMessage,
      stream: OrchestrationStream,
      db: String
  ): Boolean {
    val socket = stream.stream.socket
    val tokenBucket = openConns.get(socket)
    if (tokenBucket == null) {
      // something went really wrong
      return true
    }
    val wait = tokenBucket.requestTokens(1u)
    if (wait == null) {
      return true
    }
    stream.error(2000u, "Rate limit exceeded")
    return false
  }
}

class ThrottleDataTransferPerConnection(
    val maxBytesPerSecPerConn: UInt,
    val scheduledExecutor: ScheduledExecutorService,
) : NullServerPolicy() {

  val openConns: ConcurrentMap<MuxedSocket, LeakyBucket> = ConcurrentHashMap()

  override fun notifySocketCreated(socket: MuxedSocket, db: String) {
    openConns.put(socket, LeakyBucket(maxBytesPerSecPerConn.toDouble()))
    socket.observeLifecycle { state ->
      when (state) {
        MuxedSocket.State.IDLE,
        MuxedSocket.State.AUTH_RECV,
        MuxedSocket.State.CLOSING,
        MuxedSocket.State.CLOSE_WAIT -> Unit
        MuxedSocket.State.CLOSED -> {
          openConns.remove(socket)
        }
      }
    }
  }

  override fun shouldDeliverMessage(
      request: ProtoMessage,
      stream: OrchestrationStream,
      db: String
  ): Boolean {
    // for simplicity we allow a burst but then throttle to average
    // out the transfer rate. could always combine with a policy that
    // rejects single requests that are too large

    when (request) {
      is ProtoData -> {
        val socket = stream.stream.socket
        val bucket = openConns.get(socket)
        if (bucket == null) {
          // something went really wrong
          return true
        }

        val wait = bucket.fill(request.data.remaining())

        // for anything <= threshold, let's not worry, otherwise pause
        // and schedule resumption
        val threshold = Duration.ofMillis(1)
        if (wait.compareTo(threshold) > 0) {
          socket.channel.suspendReceives()
          scheduledExecutor.schedule(
              { socket.channel.resumeReceives() }, wait.toMillis(), TimeUnit.MILLISECONDS)
        }
        // TODO: probably want a max threshold where we error out the
        // connection. otherwise it will just timeout on the client
        // anyway. this gives us a means of explaining to the user
      }
      else -> {}
    }

    return true
  }
}

class SimpleDebugLogger(val decorated: ServerPolicy) : ServerPolicy {

  override fun shouldAcceptConnection(db: String): Boolean {
    val shouldAccept = decorated.shouldAcceptConnection(db)
    val phrase = if (shouldAccept) "accepted" else "rejected"
    System.err.println("Connection for db ${db} ${phrase}.")
    return shouldAccept
  }

  override fun notifySocketCreated(socket: MuxedSocket, db: String) {
    System.err.println("Socket created: ${socket} for db ${db}.")
    socket.observeLifecycle { state ->
      if (state == MuxedSocket.State.AUTH_RECV) {
        System.err.println("User authenticated on ${socket} with: ${socket.authenticatedWith}")
      }
      System.err.println("Socket ${socket} moved to state ${state} end state: ${socket.endState}")
    }
    decorated.notifySocketCreated(socket, db)
  }

  override fun shouldDeliverMessage(
      request: ProtoMessage,
      stream: OrchestrationStream,
      db: String
  ): Boolean {
    val shouldHandle = decorated.shouldDeliverMessage(request, stream, db)
    val phrase = if (shouldHandle) "accepted" else "rejected"
    System.err.println("Request ${request} on stream ${stream} was ${phrase}")
    val muxstream = stream.stream
    muxstream.observeLifecycle { state ->
      System.err.println(
          "Stream ${stream} initiated by request ${request} moved to state ${state} end state: ${muxstream.endState.get()}")
    }
    return shouldHandle
  }

  override fun shouldEmitMessage(
      msg: ProtoMessage,
      stream: OrchestrationStream,
      db: String
  ): Boolean {
    val shouldEmit = decorated.shouldEmitMessage(msg, stream, db)
    val phrase = if (shouldEmit) "allowed" else "blocked"
    System.err.println("Sending ${msg} on stream ${stream} was ${phrase}")
    return shouldEmit
  }
}

class SkdbBackedEventLogger() : ServerPolicy {

  private var stream: BufferedWriter? = null
  init {
    val skdb = openSkdb(SERVICE_MGMT_DB_NAME)!!
    val proc = skdb.sqlStream(OutputFormat.RAW)
    stream = proc.outputStream.bufferedWriter()
    stream?.write(
        """CREATE TABLE IF NOT EXISTS
             server_events (
               t INTEGER,
               db STRING,
               user STRING,
               event STRING,
               metadata STRING
             );""")
    stream?.write("\nCOMMIT;\n")
  }

  private fun log(db: String, event: String, user: String? = null, metadata: String? = null) {
    val t = Instant.now().getEpochSecond()
    val u = if (user == null) "NULL" else "'${user}'"
    val md = if (metadata == null) "NULL" else "'${metadata}'"
    stream?.write("INSERT INTO server_events VALUES (${t}, '${db}', ${u}, '${event}', ${md});\n")
    stream?.write("COMMIT;\n")
    stream?.flush() // TODO: server interaction is currently infrequent. as it rises, remove this.
  }

  override fun shouldAcceptConnection(db: String): Boolean {
    log(db, "conn_attempt")
    return true
  }

  override fun notifySocketCreated(socket: MuxedSocket, db: String) {
    socket.observeLifecycle { state ->
      if (state == MuxedSocket.State.AUTH_RECV) {
        log(db, "conn_established", socket.authenticatedWith?.msg?.accessKey)
      }
    }
  }

  override fun shouldDeliverMessage(
      request: ProtoMessage,
      stream: OrchestrationStream,
      db: String
  ): Boolean {
    val user = stream.stream.socket.authenticatedWith?.msg?.accessKey
    when (request) {
      is ProtoQuery -> {
        log(db, "query", user)
      }
      is ProtoRequestTail -> {
        log(db, "establish_tail", user, request.table)
      }
      is ProtoPushPromise -> {
        log(db, "establish_push", user, request.table)
      }
      is ProtoCreateDb -> {
        log(db, "create-db", user, request.name)
      }
      is ProtoCreateUser -> {
        log(db, "create-user", user)
      }
      else -> {}
    }
    return true
  }

  override fun shouldEmitMessage(
      msg: ProtoMessage,
      stream: OrchestrationStream,
      db: String
  ): Boolean {
    return true
  }
}

class Config() {

  private data class Target(val accessKey: String?, val db: String?)

  inner class Value<T>(
      private val key: String,
      private val default: T,
      private val column: String,
      private val toT: (x: String) -> T,
  ) {

    private val cache: MutableMap<Target, T> = ConcurrentHashMap()

    fun get(accessKey: String?, db: String?): T {
      val target = Target(accessKey, db)

      val v = cache.get(target)
      if (v != null) {
        return v
      }

      val x = getSerialisedVal(key, accessKey, db, column)
      val y =
          if (x == null) {
            default
          } else {
            toT(x)
          }
      cache.set(target, y)
      return y
    }

    fun invalidate() {
      cache.clear()
    }
  }

  private val handouts: Queue<WeakReference<Value<*>>> = ConcurrentLinkedQueue()
  private val skdb = openSkdb(SERVICE_MGMT_DB_NAME)!!

  init {
    skdb.sql(
        """
      CREATE TABLE IF NOT EXISTS server_config (
        key STRING,
        user STRING,
        db STRING,
        intVal INTEGER,
        dblVal FLOAT,
        strVal STRING
      );""",
        OutputFormat.RAW)

    skdb.notify("server_config") {
      for (value in handouts) {
        value.get()?.invalidate()
      }
    }
  }

  private fun getSerialisedVal(
      key: String,
      accessKey: String?,
      db: String?,
      column: String
  ): String? {
    val uExpr = if (accessKey == null) "is NULL" else "= '${accessKey}'"
    val dExpr = if (db == null) "is NULL" else "= '${db}'"

    var row =
        skdb.sql(
            """SELECT ${column}
           FROM server_config
           WHERE key = '${key}'
           AND db ${dExpr}
           AND user ${uExpr}
           LIMIT 1;""",
            OutputFormat.RAW)

    var result = row.decodeOrThrow().trim()
    if (result != "") {
      return result
    }

    row =
        skdb.sql(
            """SELECT ${column}
           FROM server_config
           WHERE key = '${key}'
           AND db ${dExpr}
           AND user is NULL
           LIMIT 1;""",
            OutputFormat.RAW)

    result = row.decodeOrThrow().trim()
    if (result != "") {
      return result
    }

    row =
        skdb.sql(
            """SELECT ${column}
           FROM server_config
           WHERE key = '${key}'
           AND db is NULL
           AND user is NULL
           LIMIT 1;""",
            OutputFormat.RAW)

    result = row.decodeOrThrow().trim()
    if (result != "") {
      return result
    }

    return null
  }

  fun getInt(key: String, default: Int): Value<Int> {
    val v = Value<Int>(key, default, column = "intVal", String::toInt)
    handouts.add(WeakReference(v))
    return v
  }

  fun getDouble(key: String, default: Double): Value<Double> {
    val v = Value<Double>(key, default, column = "dblVal", String::toDouble)
    handouts.add(WeakReference(v))
    return v
  }

  fun getString(key: String, default: String): Value<String> {
    val v = Value<String>(key, default, column = "strVal", String::toString)
    handouts.add(WeakReference(v))
    return v
  }
}
