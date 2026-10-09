# JavaZK-Lib

Java library for communicating with standalone ZKTeco attendance readers over
UDP. It supports user profiles, attendance records, realtime attendance events,
device settings, fingerprint enrollment, and fingerprint template export/import.

The implementation builds on the [zk-protocol specification](https://github.com/adrobinoga/zk-protocol)
and the template transfer layouts used by [pyzk](https://github.com/fananimi/pyzk/blob/master/zk/base.py).
Device and firmware compatibility must be verified on the reader being used.

## Build and test

Download packaged builds from [GitHub Releases](https://github.com/kenny516/ZKTeco-Biometric-lib/releases).
The current prerelease is [v1.0.0-beta.2](https://github.com/kenny516/ZKTeco-Biometric-lib/releases/tag/v1.0.0-beta.2).
It adds bulk user/fingerprint reading, simplifies terminal operations and examples,
and includes the French API Javadoc.

| Download | Use |
| --- | --- |
| `ZKTeco4J-1.0.0-beta.2.jar` | Library classes; supply the runtime dependencies from `pom.xml`. |
| `ZKTeco4J-1.0.0-beta.2-all.jar` | Library with runtime dependencies included, convenient for a manually configured Java classpath. |
| `ZKTeco4J-1.0.0-beta.2-sources.jar` | Sources for IDE navigation/debugging. |
| `ZKTeco4J-1.0.0-beta.2-javadoc.jar` | Extract and open `index.html`, or attach to the library in your IDE. |
| `SHA256SUMS.txt` | Checksums for the four JARs. |

These are library JARs, without an application entry point for `java -jar`.
Add the library to your application's classpath. To create the same downloads locally:

```shell
mvn -P release package
```

GitHub Actions runs tests and packages downloadable build artifacts on pushes to
`dev`/`main` and pull requests. Pushing a `v*` tag also publishes the JARs and
checksums as release assets; tags containing a hyphen are marked as prereleases.
The workflow can also be run manually for build artifacts.

The project targets Java 8 and uses Maven.

```shell
mvn test
mvn package
```

Tests cover user record encoding, JSON round trips, fingerprint reads, simulated
UDP writes, destination UID assignment, and upload failure/cleanup handling.
They do not require a physical reader. Do not use `MainTest` as an automated
test: it connects to the configured device and can modify its stored users.

## API Javadoc

Public user/fingerprint APIs and their result objects include Javadoc with parameter
ranges, return values, failure behavior, examples, and links between related APIs.
The added comments are in French. IDEs display them when hovering over methods.
Generate the HTML reference with:

```shell
mvn javadoc:javadoc
```

Open `target/site/apidocs/index.html` in a browser. The `release` profile also
attaches a `-javadoc.jar` to packaged releases, included since beta.2. Generation uses the
[Maven Javadoc Plugin](https://maven.apache.org/plugins/maven-javadoc-plugin/usage.html).

## Connect to a reader

Configure the device IP, UDP port (usually `4370`), and communication key.
The communication key is separate from the device ID and must match the reader.

```java
import com.zkteco.Enum.CommandReplyCodeEnum;
import com.zkteco.commands.ZKCommandReply;
import com.zkteco.terminal.ZKTerminal;

ZKTerminal terminal = new ZKTerminal("192.168.1.201", 4370);
ZKCommandReply connection = terminal.connect();
try {
    if (connection.getCode() == CommandReplyCodeEnum.CMD_ACK_UNAUTH) {
        connection = terminal.connectAuth(0); // Use your reader's communication key.
    }
    if (connection.getCode() != CommandReplyCodeEnum.CMD_ACK_OK) {
        throw new IllegalStateException("Connection/authentication failed");
    }
    // Run user or attendance operations here.
} finally {
    terminal.stopRealTimeLogs();
    terminal.disconnect();
}
```

The following examples assume an authenticated `terminal`. Imports include
`com.zkteco.commands.*`, `com.zkteco.Enum.UserRoleEnum`,
`java.time.Duration`, and `com.fasterxml.jackson.databind.ObjectMapper`.

## User identity and profiles

| Field | Meaning |
| --- | --- |
| `userid` | External application identifier, used to find/create/update a user. |
| `uid` | Internal numeric identifier on a particular reader. It can change after deletion or transfer to another reader. |
| `fingerIndex` | Finger slot from `0` to `9`. One user can have multiple templates. |

`getAllUsers()` returns profiles without fingerprint templates. `addUser()`
allocates the lowest free UID for a new external `userid`, or reuses the existing
UID when updating that ID. Duplicate external IDs are reported as errors.
The library supports the modern 72-byte user record; legacy 28-byte records
are not supported by `UserRecordCodec`.

```java
List<UserInfo> users = terminal.getAllUsers(); // java.util.List

UserInfo user = new UserInfo("EMP0001", "Alice", "1234",
        UserRoleEnum.USER_DEFAULT, 123456789L);
user.setEnabled(true);
user.setGroupId(1);

UserWriteResult result = terminal.addUser(user);
if (result.isSuccess()) {
    System.out.println("Device UID: " + result.getAssignedUid());
}
```

The codec validates field sizes and ranges before writing. Profile fields include
name, password, role, card number, enabled state, group, and timezone settings.
The older `modifyUserInfo()` method remains available but is deprecated in favor
of `addUser()`.

## Capture a new fingerprint

Enrollment requires the user to follow the instructions on the physical reader.
It creates/updates the profile, then captures a template at the chosen index.
An existing template at that index is replaced. The default timeout is 60 seconds;
use the `Duration` overload to change it.

```java
UserEnrollmentResult enrollment = terminal.addUserWithFingerprint(
        user, 1, Duration.ofSeconds(90));

if (enrollment.isSuccess()) {
    // Enrollment reports the result, scores and template size, not template bytes.
    UserBiometricData captured = terminal.getUserWithFingerprints(user.getUserid(), 1);
} else if (enrollment.getProfile().isSuccess()) {
    System.out.println("Profile retained; fingerprint: "
            + enrollment.getFingerprint().getStatus());
}
```

Enrollment results distinguish success, failure, timeout, and cancellation.
A failed enrollment does not roll back the saved profile.

## Read a user with their fingerprints

`UserBiometricData` is a snapshot containing:

| Property | Contents |
| --- | --- |
| `user` | A copy of the full `UserInfo` profile. |
| `fingerprints` | Retrieved `FingerprintTemplate` objects, each with `fingerIndex` and binary `template` bytes. |
| `fingerprintReadErrors` | Per-index device database read errors retained as metadata. |

```java
// Attempt all ten slots, collecting every template returned by the reader.
UserBiometricData data = terminal.getUserWithFingerprints("EMP0001");

// Read all profiles and attempt all ten slots for every user.
List<UserBiometricData> allUsers = terminal.getAllUserWithFingerprints();

// Alternatively, request only known slots.
UserBiometricData selected = terminal.getUserWithFingerprints("EMP0001", 1, 6);

for (FingerprintTemplate fingerprint : data.getFingerprints()) {
    System.out.println("Finger " + fingerprint.getFingerIndex()
            + ": " + fingerprint.getSize() + " bytes");
}

// Lower-level API: read one template by INTERNAL UID.
byte[] template = terminal.getUserFingerprint(data.getUser().getUid(), 1);
```

`getAllUserWithFingerprints()` fetches the profile list once and reads templates
sequentially by each user's internal UID. It returns an empty list if there are
no users. Each snapshot keeps its own fingerprint read errors. Transport failures
abort the operation. This can take time on readers with many users, and realtime
logs must be stopped before calling it.

Reading uses command `88`. It handles a pending empty ACK, direct data, and
multi-packet transfers. Template reads have a ten-second deadline and restore
the socket timeout afterward. Reading templates has been exercised on a physical
reader, including multiple finger slots for one user.

Database response codes `4991` and `4993` are recorded per index while other
device errors and transport failures abort the snapshot. Code `4993` means a
database read failure; it does not by itself prove that the slot is empty.
`isComplete()` is true only when every requested read succeeded. It may be false
even when several valid templates were retrieved. Do not use it as a test for
whether the fingerprint list is empty.

## Store and restore the snapshot

Jackson serializes template bytes as Base64 and reconstructs them as `byte[]`.
Store the JSON in a file or database JSON/text column, or store the profile and
individual templates in user and fingerprint tables with binary/BLOB columns.
Database connectivity is left to the application.

The SQL Server example [create-biometric-schema.sql](scripts/sql-server/create-biometric-schema.sql)
contains two simple `CREATE TABLE` statements: `ZKUsers` and `ZKFingerprints`.
It stores templates as `VARBINARY(MAX)`, requires `CardNo` (`0` for no card),
and permits one template per user/finger index. `UserId` links both tables.
Reader UIDs, timestamps, diagnostic metadata and timezone settings are omitted.
If timezone settings are needed, add columns for them; otherwise the Java profile
defaults apply when reconstructing a user. Run the script in a database where
these tables do not yet exist.

```java
ObjectMapper mapper = new ObjectMapper();
String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
// Persist json in your application, then read it back:
UserBiometricData restored = mapper.readValue(json, UserBiometricData.class);

UserBiometricWriteResult write = terminal.addUserWithFingerprints(restored);
System.out.println(write.getMessage());
```

Import finds the destination profile by external `userid`, reusing its UID or
allocating a new one. The source UID is not used as the destination identity.
Only supplied templates are uploaded; read errors are not uploaded and omitted
slots are not explicitly deleted. To import under another external ID:

```java
UserInfo destination = restored.getUser();
destination.setUserid("TESTCOPY");
UserBiometricData copy = new UserBiometricData(destination, restored.getFingerprints());
UserBiometricWriteResult copyResult = terminal.addUserWithFingerprints(copy);
```

### How the template upload works

`UserBiometricCodec` builds a buffer with the user profile, a template association
table, and template bytes. Each table entry contains the destination UID, finger
index, and offset of that template. This is how the reader associates the bytes
with the correct user.

1. `CMD_FREE_DATA` clears the temporary buffer.
2. `CMD_PREPARE_DATA` announces the buffer size.
3. `CMD_DATA` sends chunks of at most 1,024 bytes, each acknowledged by the reader.
4. `_CMD_SAVE_USERTEMPS` (`110`) requests storage of the uploaded profile/templates.
   Its eight-byte payload contains the protocol values `12`, `0`, `8` in little-endian
   form; these are not user IDs or a template count.
5. `RefreshData()` refreshes the stored data. Cleanup frees the buffer and attempts
   to re-enable the device, including after an upload failure.

| Result accessor | Meaning |
| --- | --- |
| `getProfile()` | Result of creating/updating the profile. |
| `getAssignedUid()` | Internal UID selected on the destination reader. |
| `isFingerprintsWritten()` | Upload/refresh succeeded; also true for a successful profile-only write with no templates supplied. |
| `isDeviceRestored()` | Cleanup state reported by the import operation. |
| `isSuccess()` | Profile, template phase, and cleanup result all indicate success. |
| `getMessage()` | Details of an error or the acknowledged operation. |

Import has been tested with a simulated UDP reader. Physical recognition after
import and cross-reader template algorithm compatibility still need device-specific
validation. An acknowledged upload is not proof of successful recognition.
The operation has no rollback: a profile and possibly uploaded data can remain
after failure. Cleanup failures are retained in the result and make `isSuccess()` false.

## Realtime attendance

Load profiles before starting the listener if you want to display user names.
The callback runs on the listener thread. Do not fetch profiles or write users
inside it: those operations would compete for the same UDP socket.

```java
terminal.startRealTimeLogs(record -> System.out.println(record.getUserID()));
// Later:
terminal.stopRealTimeLogs();
```

Stop realtime logs before reading templates, importing profiles/templates, or
enrolling a fingerprint. The user biometric APIs reject calls while the listener
is marked active.

## MainTest examples

All examples are directly inside `main`; there are no command-line modes.
Edit `deviceIp`, `port`, `commKey`, `userId`, `backupFile`, `deleteAndRestore`,
and `realtime` in [MainTest.java](src/main/java/com/zkteco/MainTest.java).

The current example lists users, optionally exports the chosen user's profile
and all readable finger slots, optionally deletes/restores the user from JSON,
then starts realtime attendance. The listener displays only the user ID and name.
Press ENTER to stop.

The export block is active, `deleteAndRestore` is set to `true`, and `realtime`
is set to `false`: the current example creates a fresh backup, then runs the
deletion/reimport sequence. Set `deleteAndRestore = false` to export without
deleting the user. Set `realtime = true` to listen for attendance afterward.

```shell
mvn compile exec:java "-Dexec.mainClass=com.zkteco.MainTest"
```

Deletion/reimport requires a nonempty fingerprint list. Read errors on other
indices do not block it: only the templates present in the JSON will be restored.
An unread template will not survive deletion merely because its index is recorded
as an error. The example uses the UID read during the fresh export and confirms deletion before
reimport. Keep the backup if an operation fails; the sequence is not transactional.

`biometric-backups/` is ignored by Git and survives `mvn clean`. The JSON includes
the user's password and biometric templates; the library does not encrypt it.
No backup JSON or binary template is included in the repository.

## Protocol references

- [zk-protocol](https://github.com/adrobinoga/zk-protocol)
- [pyzk user/template operations](https://github.com/fananimi/pyzk/blob/master/zk/base.py)
- [ZKTeco4J](https://github.com/mkhoudary/ZKTeco4J/)
- [ZKTeco push protocol documentation](https://docs.nufaza.com/docs/devices/zkteco_attendance/push_protocol/)
- [ZKTeco](https://zkteco.com/)

Protocol captures from documented device tests are welcome to expand compatibility.
