# Java architecture decision

Status: **Option B chosen by Joshua on 2026-11-18**. Java and cross-VM gRPC are confirmed.
The alternatives below document the architecture choice; Option B is implemented.

## Option A: Maven modules with a JavaFX consumer window (recommended)

```text
pom.xml
protocol/       shared protobuf definitions and generated gRPC types
producer/       producer entry point, folder readers, gRPC client
consumer/       gRPC server, bounded queue, workers, storage, JavaFX UI
```

Flow: producer folders -> streamed gRPC -> bounded admission/processing -> final
video folder -> JavaFX gallery and media player. Keep UI updates on its UI thread
and upload work off it. The consumer server and window share a process.

Fits a Java-focused coursework demonstration. Clear process boundaries and one
consumer executable keep synchronization visible. Costs include JavaFX runtime
packaging, display access on the consumer VM, and verification of target codecs.

## Option B: Maven modules with a Spring Boot consumer and browser GUI

```text
pom.xml
protocol/       protobuf and gRPC contract
producer/       Java producer application
consumer/       Java gRPC server, queue, workers, storage, HTTP gallery API
  src/main/resources/static/   HTML/CSS/JavaScript player
```

Flow: Java producers -> gRPC consumer -> bounded queue/storage -> HTTP gallery
and video playback in a browser. Browser media delivery is separate from the
required producer-to-consumer gRPC path.

Fits a headless consumer VM and remote viewing. Browser playback is convenient,
but introduces HTTP media serving, frontend JavaScript, a second listener, and
browser codec checks. Confirm whether a small JavaScript UI fits the user's
intended Java-only scope before selecting it.

## Option C: One Maven project with two entry points and a Swing consumer

```text
pom.xml
src/main/proto/                  shared gRPC contract
src/main/java/.../producer/      producer entry point
src/main/java/.../consumer/      server, queue, workers, storage
src/main/java/.../ui/            Swing gallery and embedded media integration
```

Flow: producer entry point on VM 1 -> gRPC -> consumer entry point on VM 2 ->
queue/storage -> Swing gallery/player. Both processes use the same built project.

Fits a preference for a compact source tree or Swing familiarity. The simpler
build has weaker dependency separation. Video playback needs a compatible media
integration and runtime packaging; validate that cost before choosing this route.

## Recommendation and decision record

Recommend A for the Java-focused assignment: isolate the shared protocol and two
runtime components, with a directly integrated consumer window. Choose B if the
consumer VM must run without a desktop. Choose C if a single build project and
Swing are explicit preferences and media integration is acceptable.

Joshua explicitly chose B before application edits. The alternatives above are
retained as decision history.

## Approved implementation

Option B: Maven `protocol`, `producer`, `consumer` modules with a Spring Boot
HTTP gallery and JavaScript browser playback. Java 17 bytecode, Maven 3.9.11 wrapper,
Spring Boot 3.5.16, gRPC 1.85.1, protobuf 4.36.2. No database or external broker.
Windows/JDK 17 are available locally; the two target VM operating systems remain
unspecified. The consumer supports headless operation and remote browser viewing.

Queue unit: one admitted upload waiting for a consumer worker. c workers own at
most c active receive/validate/publish jobs; FIFO queue holds at most q metadata
and stream jobs. q=0 is supported. Excess metadata gets RESOURCE_EXHAUSTED without
staging. Manual inbound flow control requests one <=64 KiB chunk at a time.
ADMITTED means reserved; STORED means verified and atomically published. SHA-256,
declared size, safe filenames, generated UUIDs, and cleanup protect storage.
CLI inputs configure p/folders and c/q. Previews reset on exit and end at ten
seconds or earlier clip end. Restart retains final files and discards orphan
partials. See README.md for the complete operating contract and dependencies.
