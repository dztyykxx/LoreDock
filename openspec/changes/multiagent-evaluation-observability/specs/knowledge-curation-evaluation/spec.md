# Knowledge Curation Evaluation

## Requirements

### Requirement: Safe curation projection

The system SHALL expose an optional `curation` projection on `AGENT_STAGE` event payloads. The projection SHALL contain only validated route enums, stable IDs, bounded lists, and bounded expert names. It MUST NOT contain prompts, complete tool results, fact statements, checkpoint identifiers, graph state, or hidden reasoning. Non-`AGENT_STAGE` events MUST reject a non-null projection.

#### Scenario: Legacy event remains readable

- **WHEN** an existing event payload has no `curation` field
- **THEN** the event is read with a null projection and the existing event fields remain available

#### Scenario: Invalid projection is rejected

- **WHEN** a projection contains an unsupported enum, null stable ID, or exceeds a list/text bound
- **THEN** the event is not persisted and the caller receives a validation error

### Requirement: Trace-based curation evaluation

The evaluation runner SHALL collect final business output, GraphTrace, safe Tool summaries, run usage, and runtime definition metadata from the normal task snapshot. It MUST distinguish unavailable token usage from an actual zero value and MUST NOT read Checkpoint or Graph State directly.

#### Scenario: Short-circuit path

- **WHEN** a run takes `CHAT` or `TURN_DONE`
- **THEN** the trace records the main Agent action and no fabricated Retriever, Drafter, Reviewer, or write stage is added

#### Scenario: Full curation path

- **WHEN** a run takes `FULL_CURATION`
- **THEN** the trace records the observed stages, safe Tool ownership, evidence IDs, draft receipts, review result, and actual run cost

### Requirement: Experiment and dataset gates

The report SHALL record system variant, model/definition summaries, graph version, and a deterministic seed fingerprint. It SHALL record whether the dataset was human reviewed; an unreviewed dataset MAY produce a development report but MUST fail the formal evaluation gate.
