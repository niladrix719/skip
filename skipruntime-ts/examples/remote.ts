import type {
  Context,
  EagerCollection,
  Values,
  NamedCollections,
  Resource,
} from "@skipruntime/core";
import { ManyToOneMapper } from "@skipruntime/core";
import { SkipExternalService } from "@skipruntime/helpers";

import { initService } from "@skipruntime/wasm";
import { runService } from "@skipruntime/server";

class Mult extends ManyToOneMapper<string, number, number> {
  mapValues(values: Values<number>): number {
    return values.toArray().reduce((p, c) => p * c, 1);
  }
}

class MultResource implements Resource {
  instantiate(
    _collections: NamedCollections,
    context: Context,
  ): EagerCollection<string, number> {
    const sub = context.useExternalResource<string, number>({
      service: "sumexample",
      identifier: "sub",
    });
    const add = context.useExternalResource<string, number>({
      service: "sumexample",
      identifier: "add",
    });
    return sub.merge(add).map(Mult);
  }
}
const instance = await initService({
  resources: { data: MultResource },
  externalServices: {
    sumexample: SkipExternalService.direct({
      host: "localhost",
      streaming_port: 3587,
      control_port: 3588,
    }),
  },

  createGraph(inputCollections: NamedCollections) {
    return inputCollections;
  },
});
const service = runService(instance, {
  streaming_port: 3589,
  control_port: 3590,
});

function shutdown() {
  service.close();
}

process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
