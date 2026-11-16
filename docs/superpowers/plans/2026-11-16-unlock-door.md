# Door unlock implementation plan

**Goal:** Provide `ZKTerminal.unlockDoor(int durationSeconds)`.
**Architecture:** Existing UDP session, CMD_UNLOCK, unsigned 32-bit little endian delay in tenths of a second, following pyzk.
**Tech Stack:** Java 8, JUnit 4, Maven.
**Spec:** ../specs/2026-11-16-unlock-door-design.md

## Constraints and review focus
Return device refusals unchanged; never retry automatically. Reject nonpositive or unrepresentable durations before sending. Report missing connection and truncated replies as IOException. Physical relay behavior requires hardware validation.

## Task: API, tests and example
- [x] Add ZKTerminalDoorTest: assert command 31, 5 seconds encoded as 50, multi-byte duration, maximum unsigned delay, session/reply sequence, ACK and refusal, invalid duration, disconnected socket and truncated reply.
- [x] Run `mvn -Dtest=ZKTerminalDoorTest test` and confirm missing method.
- [x] Implement API in ZKTerminal.java using getPacketByte/sendPacket/readResponse.
- [x] Add README example including reply check and firmware limitation.
- [x] Run `mvn test` and `mvn clean package`; inspect diff.

Execution note: the user added an unlock call to MainTest while implementation was underway. Keep their call, place it after authentication, and check its reply. Do not run MainTest against hardware.
