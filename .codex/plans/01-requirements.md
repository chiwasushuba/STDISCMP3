# Requirements and acceptance evidence

Source: `../../requirements.txt`. Language: Java (user decision).
Status: implemented and locally verified on Windows / JDK 17. Thirty-two Java tests
and fourteen browser scenarios pass. The table below retains the acceptance criteria;
actual evidence is in `../../docs/verification.md`. R7 (two VMs) is unverified,
D2 has a local browser recording only, and D3 has editable slide content but no
exported PowerPoint. Official instructor cases remain pending.

| ID | Mandatory requirement | Planned acceptance evidence |
| --- | --- | --- |
| R1 | Producers read videos and upload through gRPC | Actual gRPC transfer; compare input/output bytes |
| R2 | User configures p producers, each using a separate folder | Run multiple producers with distinguishable folder fixtures; reject invalid mappings |
| R3 | User configures c consumer threads | Hold processing work and observe c worker concurrency under the approved worker definition |
| R4 | User configures q maximum queue length; excess videos are dropped | Force saturation; queued occupancy never exceeds q; verify excess work is dropped and cleaned |
| R5 | Accept simultaneous media uploads | Concurrent real gRPC clients; integrity and completion checks for every accepted file |
| R6 | Save uploaded videos to one folder | Completed videos share the configured output directory; no incomplete gallery entries or overwrites |
| R7 | Producer and consumer run on different VMs | Record a two-VM execution using reachable consumer address and ports |
| R8 | Consumer has a window or browser GUI | Launch consumer UI; completed uploads appear while it remains responsive |
| R9 | Hover previews ten seconds | Time hover playback; preview stops at ten seconds or earlier end of file |
| R10 | Click displays/plays video | Open a completed item and verify ordinary full playback |
| D1 | Source code and build/compilation steps | Clean build using documented pinned prerequisites and commands |
| D2 | Video demonstration | Record working mandatory cases; add instructor cases when provided |
| D3 | Slides | Explain key steps, queueing, producer/consumer concepts, and synchronization using actual implementation evidence |

## Bonus scope

| ID | Optional behavior | Planned evidence if selected |
| --- | --- | --- |
| B1 | Consumer tells producers the queue is full | Observable overload response, distinct from successful completion |
| B2 | Duplicate detection | Identical content under different names plus simultaneous duplicate attempts |
| B3 | Video compression | Playable compressed output; measured original/result size and processing cost |

## Decisions to settle before their implementation

- GUI and module architecture: see `02-architecture.md`.
- JDK/build versions, target VM OS, and supported media formats/codecs.
- Queue unit: waiting upload jobs versus staged complete files; define capacity
  separately from active workers and transport buffering.
- Whether p/c/q are CLI options, interactive prompts, or GUI inputs; validate
  counts and folder availability. Decide whether q=0 is supported explicitly.
- Preview policy for hover exit, re-entry, and clips shorter than ten seconds.
- Completion semantics and restart behavior. The assignment does not require
  a persistent job queue; document what happens to unfinished work on restart.

Do not turn these proposals into extra assignment requirements. Instructor test
cases have not been supplied; all planned scenarios above are provisional.
