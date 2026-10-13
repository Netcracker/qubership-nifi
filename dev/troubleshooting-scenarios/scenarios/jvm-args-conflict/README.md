# Conflicting JVM arguments

`NIFI_ADDITIONAL_JVM_ARGS` and `X_JAVA_ARGS` select two different garbage collectors. The start script checks the
collector arguments with a trial JVM before it starts NiFi, and exits with code 3 when that JVM refuses them.

```bash
./run.sh jvm-args-conflict
```

## Symptom

- The container exits with code 3 about 10 seconds after the start, before NiFi writes `nifi-app.log`.
- The container output ends with:

  ```text
  ERROR: Invalid JVM arguments in NIFI_ADDITIONAL_JVM_ARGS + X_JAVA_ARGS: -XX:+UseG1GC -XX:+UseParallelGC
  Picked up JAVA_TOOL_OPTIONS: -XX:+UseParallelGC
  Error occurred during initialization of VM
  Multiple garbage collectors selected
  ```

## Cause

The start script adds both variables to `conf/bootstrap.conf` as `java.arg.<n>` entries. It checks only the
arguments that select a garbage collector (`-XX:+Use...GC`, `-XX:-Use...GC`) and
`-XX:+UnlockExperimentalVMOptions`. The image entrypoint also exports `X_JAVA_ARGS` as `JAVA_TOOL_OPTIONS`, so the
trial JVM and NiFi pick it up; the trial JVM prints the `Picked up JAVA_TOOL_OPTIONS` line. From 2.8.6, the Consul
application starts without `JAVA_TOOL_OPTIONS` and takes its JVM options from `CONSUL_CONFIG_JAVA_OPTIONS`.
See [Invalid JVM arguments](../../../../docs/troubleshooting-guide.md#1-invalid-jvm-arguments) in the
troubleshooting guide.

## How the scenario reproduces it

NiFi starts once without the variables. `scenario.sh` then creates the NiFi container again with
`NIFI_ADDITIONAL_JVM_ARGS` set to `JVM_ARGS_ADDITIONAL` and `X_JAVA_ARGS` set to `JVM_ARGS_X_JAVA`.

| Variable              | Default              | Description                                    |
|-----------------------|----------------------|------------------------------------------------|
| `JVM_ARGS_ADDITIONAL` | `-XX:+UseG1GC`       | `NIFI_ADDITIONAL_JVM_ARGS` on the second start |
| `JVM_ARGS_X_JAVA`     | `-XX:+UseParallelGC` | `X_JAVA_ARGS` on the second start              |

## Expected evidence

| File                    | What to look for                                                                                                    |
|-------------------------|---------------------------------------------------------------------------------------------------------------------|
| `manifest.txt`          | `nifi_exit_code=3`                                                                                                  |
| `container.log`         | `ERROR: Invalid JVM arguments in NIFI_ADDITIONAL_JVM_ARGS + X_JAVA_ARGS` and `Multiple garbage collectors selected` |
| `nifi-app.log.gz`       | A single line saying that `nifi-app.log` does not exist: NiFi itself never started                                  |
| `scenario-jvm-args.txt` | Both variables, the `java.arg.*` entries of `bootstrap.conf` with the two collectors, and the startup errors        |

## NiFi 1.x

NiFi 1.x has no trial JVM check. The start script adds both variables to `bootstrap.conf`, and the NiFi JVM itself
fails to start. The container exits with code 0, not 3, about 6 seconds after the start, and its output does not
name the failure. RunNiFi logs the JVM output to `nifi-app.log`, where the stdout and stderr lines can interleave in
either order; NiFi 1.x writes no `nifi-bootstrap.log`:

```text
[INFO ] ... [class=org.apache.nifi.StdOut] ... Error occurred during initialization of VM
[INFO ] ... [class=org.apache.nifi.StdOut] ... Multiple garbage collectors selected
[ERROR] ... [class=org.apache.nifi.StdErr] ... Picked up JAVA_TOOL_OPTIONS: -XX:+UseParallelGC
[INFO ] ... [class=org.apache.nifi.bootstrap.RunNiFi] ... NiFi never started. Will not restart NiFi
```

`manifest.txt` has `nifi_exit_code=0`, and the startup errors in `scenario-jvm-args.txt` come from `nifi-app.log`.
The remediation is the same. Measured on qubership-nifi 1.0.9 (Apache NiFi 1.28.1).

## Remediation

Keep one `-XX:+Use...GC` option across `NIFI_ADDITIONAL_JVM_ARGS`, `X_JAVA_ARGS`, and `JAVA_TOOL_OPTIONS`, then
restart NiFi.
