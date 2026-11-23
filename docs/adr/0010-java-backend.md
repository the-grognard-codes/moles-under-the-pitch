# Retain Java and modernize the runtime independently

Status: accepted by the owner on 2026-09-06. [Acceptance and source](../../.notes/overhaul-analysis/04-technology-and-decisions.md#adr-001-java-backend-modernize-runtime-independently) · [September 6 revision](https://github.com/the-grognard-codes/moles-under-the-pitch/blob/120dd9953a71d7b0bff031c751feffae7f55b5f8/.notes/overhaul-analysis/04-technology-and-decisions.md#adr-001-java-backend-modernize-runtime-independently)

## Amendments and follow-up

- 2026-10-30. The owner accepted Java 21 as the sole build and runtime baseline. This ends the Java 8 comparison period. [Java 21 baseline outcome](../specs/java21-baseline-outcome.md)

## Original decision, 2026-09-06

Decision status: **accepted**. The proposal below is now the agreed direction.

**Proposed:** keep Java and Maven; target Java 21 for the new server runtime, after baseline checks and dependency compatibility work. Preserve the Java 8 baseline as a comparison during migration. The current root POM already describes a Java 21 Mockito profile; that is evidence of intended build support, not proof of production runtime compatibility.

Keep embedded Jetty as the first server-framework candidate, upgrading to a supported 12.x line with compatibility tests before public deployment. Existing server POM pins Jetty **9.4.0.v20161208**, and Jetty's official lifecycle page marks 9.4 EOL; Jetty 12 requires Java 17 or newer. This is a verified maintenance gap, not a claim of a demonstrated vulnerability. [Server POM, WebSocket dependencies](https://github.com/the-grognard-codes/moles-under-the-pitch/blob/120dd9953a71d7b0bff031c751feffae7f55b5f8/ffb-server/pom.xml#L94); [Jetty support table](https://jetty.org/download.html).

**Alternatives:** Kotlin can call Java and could gradually host adapters, but adds language/tooling burden without removing the existing architecture. Spring Boot is reasonable if authentication, administration, and web APIs grow substantially; its current requirements start at Java 17 and represent a framework migration, not a drop-in Java 8 wrapper. Node.js is useful for frontend tooling, but a Node game server either duplicates the rules or introduces another service calling Java. None is justified for the first slice. [Kotlin interoperability](https://kotlinlang.org/docs/java-interop.html); [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html).

**Keep initially:** MariaDB/JDBC, with an isolated disposable local instance and versioned schema setup. Changing database technology while changing the client adds a second durability risk. The README's old MySQL connector compatibility statement is not the current POM: the declared driver is MariaDB Java client 3.5.8. Verify a supported server/driver combination in the local startup milestone; no database compatibility test was performed by the source audit. [Readme startup section](https://github.com/the-grognard-codes/moles-under-the-pitch/blob/120dd9953a71d7b0bff031c751feffae7f55b5f8/Readme.md); [declared dependency inventory](https://github.com/the-grognard-codes/moles-under-the-pitch/blob/120dd9953a71d7b0bff031c751feffae7f55b5f8/.notes/overhaul-analysis/evidence/dependencies.csv).
