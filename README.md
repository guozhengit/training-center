# Interview Training Center

The interview training center is a local Java 17 Maven reactor for coding, oral, and project-defense practice. Its source catalog remains the read-only `output/coding-ai-exam` tree; mutable data belongs under `output/training-runtime`.

## Prerequisites

- JDK 17 at `D:\jdk\jdk-17.0.12`
- Maven 3.9.9 at `D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd`
- The existing Python environment used by the coding-question project

## Build

Run Maven with the approved JDK:

```powershell
$env:JAVA_HOME = 'D:\jdk\jdk-17.0.12'
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' -f training-center/pom.xml test
```

`training-core` owns shared domain and environment checks. Its `TrainingPaths` configuration makes the workspace, catalog, runtime, sandbox, export, log, and database roots explicit. `training-cli` is reserved for the later command-line adapter. The `verify` Maven profile enforces Java 17.
