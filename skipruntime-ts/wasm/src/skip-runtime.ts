export { initService } from "./skipruntime_init.js";

export type {
  Mapper,
  Param,
  EagerCollection,
  NonEmptyIterator,
  LazyCollection,
  LazyCompute,
  Context,
  TJSON,
  JSONObject,
  Accumulator,
  Entry,
  ReactiveResponse,
  SkipService,
  Resource,
  ExternalService,
  CollectionUpdate,
  Watermark,
  SubscriptionID,
} from "@skipruntime/api";

export type {
  Values,
  ServiceInstance,
} from "./internals/skipruntime_module.js";
export { UnknownCollectionError } from "@skipruntime/helpers/errors.js";
export { OneToOneMapper, ManyToOneMapper } from "@skipruntime/api";
export { freeze } from "./internals/skipruntime_module.js";
export {
  Sum,
  Min,
  Max,
  parseReactiveResponse,
  reactiveResponseHeader,
  ExternalSkipService,
  type ExternalResource,
  GenericExternalService,
  Polled,
} from "@skipruntime/helpers";

export {
  fetchJSON,
  SkipRESTService,
  type Entrypoint,
} from "@skipruntime/helpers/rest.js";

