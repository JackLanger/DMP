# Portal & Service Ecosystem --- Architecture and Development Path

**Status:** Initial architecture draft\
**Date:** 2026-11-04

## 1. Architectural goal

Build a decentralized, event-driven service ecosystem in which
independently deployable services expose capabilities and communicate
without routing all traffic through `Portal.Server`.

`Portal.Server` is the user-facing application layer. It provides a
unified interface to the available services, but it is not the central
message dispatcher and is not a prerequisite for service-to-service
communication.

The architectural principle is:

> Services announce what they can do. Kafka distributes events.
> Interested services independently react. Portal discovers the
> available capabilities and exposes them to users.

## 2. Architectural boundaries

  -----------------------------------------------------------------------
  Component                           Responsibility
  ----------------------------------- -----------------------------------
  **Portal.Server**                   User-facing API,
                                      authentication/authorization, UI
                                      integration, capability
                                      presentation and user-level
                                      workflows

  **Kafka**                           Decentralized asynchronous event
                                      and command distribution

  **Service Registry / local          Current view of service instances,
  projections**                       availability and capabilities

  **Individual services**             Own their business logic, data and
                                      lifecycle; independently produce
                                      and consume Kafka messages

  **gRPC**                            Direct synchronous
                                      service-to-service requests where
                                      an immediate response is required

  **Storage infrastructure**          Persistent files, indexes, vector
                                      data and service-owned state
  -----------------------------------------------------------------------

### Portal.Server

Portal is responsible for presenting the service ecosystem to clients.

It should: - Expose a unified client-facing API. - Discover services by
consuming their Kafka heartbeat announcements. - Maintain a local
projection of currently available service instances and capabilities. -
Dynamically expose available tasks to the UI. - Coordinate user-facing
workflows where coordination is genuinely needed. - Consume relevant
processing and lifecycle events to update client-facing state.

It should not: - Dispatch every service-to-service message. - Be
required for services to communicate. - Proxy all Kafka traffic. - Own
the business logic of individual services. - Be the authoritative owner
of every service's availability.

Portal is one participant in the ecosystem, not its central transport
layer.

### Kafka

Kafka is the shared asynchronous communication backbone.

It should: - Distribute events to all interested consumer groups. -
Allow services to publish and consume independently. - Support
asynchronous commands where appropriate. - Decouple producers from
consumers. - Allow new consumers to react to existing event streams
without changing producers.

Kafka is not a general-purpose synchronous RPC mechanism. Use direct
service APIs when a caller needs an immediate result.

### Individual services

Each service: - Owns its implementation and business responsibilities. -
Publishes events when relevant state changes occur. - Consumes events
relevant to its responsibilities. - Can communicate with other services
through direct APIs when appropriate. - Does not need to know that
Portal exists.

## 3. Service discovery through heartbeat events

Discovery should be passive from Portal's perspective. Portal does not
periodically call every service to ask whether it is alive.

Instead, each service periodically publishes a `ServiceHeartbeat` event
to Kafka. The event contains the service identity and the tasks
currently offered by that instance.

### Proposed event contract

``` json
{
  "eventType": "ServiceHeartbeat",
  "serviceId": "llm-service",
  "instanceId": "instance-7f31",
  "timestamp": "2026-11-04T19:00:00Z",
  "availableTasks": [
    "text.generate",
    "text.summarize",
    "embeddings.generate"
  ]
}
```

This is an initial contract, not a final schema. Add fields only when a
concrete consumer requires them.

### Identity

Keep logical service identity separate from process/instance identity:

-   `serviceId`: identifies the logical service, for example
    `llm-service`.
-   `instanceId`: identifies one running instance of that service.

This supports multiple instances of the same service and lets consumers
track them independently.

### Available tasks

`availableTasks` describes the capabilities currently offered by the
instance.

Examples: - `document.upload` - `document.download` - `document.print` -
`text.generate` - `text.summarize` - `embeddings.generate` -
`document.index`

Use stable, versionable capability identifiers. Avoid exposing internal
implementation details to Portal.

A heartbeat should communicate a current capability snapshot, not merely
that the process is alive. If capabilities change while the service is
running, the next heartbeat advertises the updated set.

### Discovery lifecycle

1.  A service starts.
2.  It publishes a `ServiceHeartbeat`.
3.  Portal consumes the event and adds or updates that instance in its
    local projection.
4.  The service periodically publishes another heartbeat.
5.  Portal refreshes the instance's last-seen time and capability
    snapshot.
6.  If heartbeats stop, Portal marks the instance unavailable after a
    configurable timeout.
7.  On graceful shutdown, the service may publish an explicit
    `ServiceUnregistered` event to accelerate removal.

The heartbeat is the normal discovery and liveness mechanism. A separate
registration handshake with Portal is not required.

### Initial timing assumptions

These are starting values to validate under real deployment conditions:

  Setting                                 Initial value
  --------------------- -------------------------------
  Heartbeat interval                          5 seconds
  Unavailable timeout                    15--20 seconds
  Graceful shutdown       Publish `ServiceUnregistered`

Do not treat a missed heartbeat as proof that a service has permanently
disappeared. Mark it unavailable and allow a later heartbeat to restore
availability.

### Who consumes heartbeats?

Portal is not the only possible consumer. Other infrastructure
components may independently maintain projections:

-   Portal: capability discovery and UI presentation.
-   Monitoring: service health and availability.
-   Scheduler: service selection or workload placement.
-   Registry component: an optional shared authoritative view, if one
    becomes necessary.

Do not introduce a central registry service merely to forward heartbeat
messages. Start with independent consumers and add a shared registry
only if a concrete requirement justifies it.

## 4. Kafka consumer-group semantics

Multiple independent services must be able to react to the same event.

For example, `DocumentUploaded` may be consumed by: -
`data-processing-service` - `index-service` - `portal-server` -
`monitoring`

Each logical consumer uses its own Kafka consumer group. Each group
receives the event independently.

Within one consumer group, Kafka distributes partitions among instances
of that group. This is useful for scaling multiple instances of the same
service.

Do not put unrelated services in the same consumer group if each service
must receive every event.

## 5. Example: document upload and processing

Assume the ecosystem contains:

-   File Service
-   Data Processing Service
-   Index Service
-   Two LLM Services
-   Print Service
-   Portal.Server

### Upload flow

1.  A client requests an upload through Portal.
2.  Portal calls the File Service directly through its synchronous API.
3.  File Service persists the document.
4.  File Service publishes `DocumentUploaded`.
5.  Data Processing Service consumes `DocumentUploaded`.
6.  Data Processing Service retrieves the document from File Service
    through its API.
7.  It extracts and transforms the content.
8.  It generates or requests embeddings and writes the resulting data to
    vector storage.
9.  It publishes `DocumentContentProcessed` when processing completes.
10. Index Service independently consumes `DocumentUploaded` and updates
    its document index.
11. Index Service may also consume `DocumentContentProcessed` if it
    needs the transformed content.
12. Portal independently consumes relevant events and updates the
    user-facing processing state.

File Service does not need to know about Data Processing Service, Index
Service or Portal. The consumers do not need to know about each other.

### Read flow

If Index Service needs a document's contents immediately, it calls File
Service directly through gRPC (or another synchronous service API). It
does not need to publish a Kafka event and wait for a response.

### LLM flow

LLM services can publish generated-data events independently. Other
interested services consume those events. Portal does not need to relay
them.

## 6. Events, commands and queries

Keep message intent explicit.

  -----------------------------------------------------------------------
  Message type            Meaning                 Typical transport
  ----------------------- ----------------------- -----------------------
  **Event**               Something has happened  Kafka

  **Command**             Someone requests that   Kafka for asynchronous
                          work be performed       work; gRPC for
                                                  synchronous work

  **Query**               Someone requests        gRPC or another
                          existing information    synchronous API
  -----------------------------------------------------------------------

Examples:

  Message                      Type
  ---------------------------- ---------
  `DocumentUploaded`           Event
  `DocumentContentProcessed`   Event
  `DocumentIndexed`            Event
  `GenerateEmbeddings`         Command
  `RebuildIndex`               Command
  `GetDocumentContent`         Query
  `GetDocumentMetadata`        Query

An event describes a fact that has already happened. A command expresses
an intention and should have a clearly defined responsible consumer.

Avoid using Kafka as generic request/response RPC. For asynchronous
commands, publish completion or failure events and correlate them with
the originating request.

## 7. Reliability and contract considerations

These concerns should be addressed before the system is considered
production-ready:

-   **Duplicate delivery:** Design consumers to be idempotent. Typical
    Kafka processing is at-least-once, so duplicate handling is
    required.
-   **Correlation IDs:** Carry a correlation ID across events and
    commands belonging to the same workflow.
-   **Event IDs:** Give each event a stable unique identifier for
    deduplication and tracing.
-   **Schema evolution:** Version and evolve event contracts compatibly.
    Avoid coupling every service to a shared implementation library;
    share schemas/contracts instead.
-   **Partitioning:** Choose keys that preserve ordering where needed,
    such as a document ID for document lifecycle events.
-   **Retries and dead-letter handling:** Define retry policies and a
    dead-letter strategy for poison messages.
-   **Observability:** Include structured logs, trace/correlation
    context, consumer lag and processing-failure metrics.
-   **Availability semantics:** Distinguish process liveness from task
    availability. A service can be alive while a particular capability
    is unavailable.
-   **Endpoint information:** If Portal must invoke a discovered
    capability, the service announcement or a separate service
    description must eventually provide a resolvable endpoint or routing
    mechanism. Do not assume `serviceId` alone is sufficient to make a
    direct connection.

## 8. Development path

Implement the architecture incrementally. Avoid building a
general-purpose platform before validating the basic communication
model.

### Phase 1 --- Define contracts and boundaries

-   [ ] Define service identity: `serviceId` and `instanceId`.
-   [ ] Define the initial `ServiceHeartbeat` schema.
-   [ ] Define capability naming conventions.
-   [ ] Define event envelope fields: event ID, event type, timestamp,
    correlation ID and schema version.
-   [ ] Define initial Kafka topic and consumer-group conventions.
-   [ ] Decide how service endpoints are advertised and resolved for
    direct calls.
-   [ ] Document which interactions use Kafka and which use gRPC.

**Deliverable:** A small, versioned set of messaging contracts and
architecture decisions.

### Phase 2 --- Prove decentralized messaging

-   [ ] Start Kafka locally.
-   [ ] Implement two independent test services.
-   [ ] Publish one test event from the first service.
-   [ ] Consume it independently from both services.
-   [ ] Verify that separate consumer groups each receive the event.
-   [ ] Verify that multiple instances in one group share partition
    work.
-   [ ] Test consumer restart and event replay behavior.

**Deliverable:** A working demonstration that Kafka distributes events
without Portal mediation.

### Phase 3 --- Implement service heartbeat discovery

-   [ ] Implement periodic heartbeat publication in a service.
-   [ ] Include service ID, instance ID, timestamp and available tasks.
-   [ ] Implement graceful `ServiceUnregistered` publication.
-   [ ] Implement a Portal heartbeat consumer.
-   [ ] Maintain a local service-instance projection.
-   [ ] Implement heartbeat expiration and recovery after a service
    returns.
-   [ ] Test multiple instances of one logical service.
-   [ ] Test capability changes while a service is running.

**Deliverable:** Portal can dynamically discover available service
instances and capabilities without polling them.

### Phase 4 --- Establish service communication

-   [ ] Define service API contracts for synchronous operations.
-   [ ] Implement direct gRPC communication where an immediate result is
    required.
-   [ ] Keep Kafka consumers and producers inside service integration
    boundaries.
-   [ ] Verify that services continue to communicate when Portal is
    stopped.
-   [ ] Verify that a new consumer can be added without changing
    existing producers.

**Deliverable:** Independent services using Kafka and direct APIs
without Portal as an intermediary.

### Phase 5 --- Implement the document workflow

-   [ ] Implement document upload in File Service.
-   [ ] Publish `DocumentUploaded` after successful persistence.
-   [ ] Implement Data Processing Service consumption.
-   [ ] Retrieve document contents through File Service API.
-   [ ] Extract and transform content.
-   [ ] Store embeddings in vector storage.
-   [ ] Publish processing completion/failure events.
-   [ ] Implement Index Service consumption and index updates.
-   [ ] Add correlation IDs and idempotent processing.
-   [ ] Expose processing state to Portal through consumed events.

**Deliverable:** An end-to-end asynchronous document-processing
workflow.

### Phase 6 --- Dynamic Portal integration

-   [ ] Consume service discovery announcements.
-   [ ] Expose currently available capabilities through the Portal API.
-   [ ] Map capability identifiers to UI functionality.
-   [ ] Handle unavailable services and capabilities.
-   [ ] Support multiple instances of a capability.
-   [ ] Keep service-specific business logic outside Portal.
-   [ ] Define how Portal selects or addresses a service instance when a
    user invokes a capability.

**Deliverable:** Portal dynamically presents and invokes capabilities
offered by the running service ecosystem.

### Phase 7 --- Production hardening

-   [ ] Define retry and dead-letter policies.
-   [ ] Validate schema compatibility.
-   [ ] Add metrics for consumer lag, processing duration and failures.
-   [ ] Add distributed tracing and correlation propagation.
-   [ ] Test broker outages and service restarts.
-   [ ] Test duplicate and out-of-order event handling.
-   [ ] Define authentication and authorization for Kafka and service
    APIs.
-   [ ] Validate partition counts, replication and retention policies.
-   [ ] Document deployment, configuration and operational procedures.

**Deliverable:** A resilient, observable and deployable service
ecosystem.

## 9. Decisions intentionally left open

These should be decided when implementation requirements become
concrete:

1.  Whether service discovery remains a Portal-local projection or
    evolves into a dedicated registry component.
2.  Whether service endpoints are included in heartbeats or resolved
    through a separate service description.
3.  Whether capability selection is performed by Portal, a dedicated
    scheduler, or the invoking service.
4.  Whether asynchronous commands use dedicated command topics or a
    shared topic with explicit routing metadata.
5.  Which event schema format and compatibility policy to adopt.
6.  Whether workflow orchestration is implemented in Portal for
    user-facing workflows or in dedicated workflow-owning services for
    longer-running domain processes.

## 10. Core architectural principles

1.  Portal is the user-facing interface, not the central dispatcher.
2.  Kafka is a decentralized event backbone shared by all services.
3.  Every service can independently publish and consume events.
4.  One event can be consumed by multiple independent consumer groups.
5.  Services own their capabilities, business logic and data.
6.  Service discovery is based on heartbeat announcements, not Portal
    polling.
7.  Heartbeats advertise service identity and currently available tasks.
8.  Use direct APIs for synchronous queries and operations requiring
    immediate responses.
9.  Do not introduce central components without a concrete
    responsibility that requires them.

The target is a service ecosystem in which Portal can disappear
temporarily without stopping communication and processing between
independent services.
