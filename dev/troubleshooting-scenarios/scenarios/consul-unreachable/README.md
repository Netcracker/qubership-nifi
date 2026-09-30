# Consul unreachable

NiFi is started while Consul cannot be reached. The Consul integration application, which prepares `nifi.properties` and
`logback.xml` before NiFi starts, cannot read its configuration. How the startup ends depends on how Consul is
unreachable:

- When the host in `CONSUL_URL` does not resolve, the application terminates, and the container exits with code 1.
- When Consul accepts the connection and does not respond, the application waits for the response without a timeout,
  and the startup hangs.

```bash
./run.sh consul-unreachable
CONSUL_FAILURE=no-response ./run.sh consul-unreachable
```

## Symptom

With `CONSUL_FAILURE=wrong-host`:

- The container exits with code 1 about 10 seconds after the start, before NiFi writes `nifi-app.log`.
- The container output has a Spring Boot failure report, and then the start script error:

  ```text
  ***************************
  APPLICATION FAILED TO START
  ***************************

  Description:

  Config data resource '[ConsulConfigDataResource@... context = 'config/local/qubership-nifi,default/', ...]' via location 'consul:consul-wrong:8500' does not exist

  Action:

  Check that the value 'consul:consul-wrong:8500' at class path resource [application.yaml] from qubership-nifi-consul-application.jar - 66:13 is correct, or prefix it with 'optional:'

  ERROR: Consul app java process has terminated prematurely. See logs for details...
  ```

With `CONSUL_FAILURE=no-response`:

- The container keeps running and never becomes healthy. It writes no error.
- The last line of the container output repeats once a second:

  ```text
  Checking if consul app main execution completed
  ```

- The main thread of the Consul integration application (`qubership-nifi-consul-application.jar`) waits in
  `org.springframework.cloud.consul.config.ConsulPropertySource.init` for the HTTP response.

## Cause

The start script runs the Consul integration application first and starts NiFi only after that application has
written its configuration. Consul is unreachable when `CONSUL_URL` is wrong, or when the network or Consul does not
let requests through. See [Invalid Consul URL](../../../../docs/troubleshooting-guide.md#2-invalid-consul-url) in the
troubleshooting guide.

## How the scenario reproduces it

NiFi starts once with a working Consul. `scenario.sh` then creates the NiFi container again with Consul unreachable,
by `CONSUL_FAILURE`:

- `wrong-host`: `CONSUL_URL` is `WRONG_CONSUL_URL`. `run.sh` waits for the container to exit.
- `no-response`: the Consul container is paused with `docker pause`, and `CONSUL_URL` stays `consul:8500`. The
  scenario waits `HANG_SECONDS` and collects the evidence while the startup hangs. `cleanup.sh` removes the paused
  container.

The scenario uses `NIFI_CONSUL_INT_FRAMEWORK=spring`, as the base compose file does.

| Variable           | Default             | Description                                                                |
|--------------------|---------------------|----------------------------------------------------------------------------|
| `CONSUL_FAILURE`   | `wrong-host`        | `wrong-host` or `no-response`                                              |
| `WRONG_CONSUL_URL` | `consul-wrong:8500` | `CONSUL_URL` on the second start with `CONSUL_FAILURE=wrong-host`          |
| `HANG_SECONDS`     | `120`               | Seconds the startup waits for Consul before the evidence is collected, with `CONSUL_FAILURE=no-response` |

## Expected evidence

| File                  | What to look for                                                                                           |
|-----------------------|------------------------------------------------------------------------------------------------------------|
| `manifest.txt`        | `wrong-host`: `nifi_exit_code=1`. `no-response`: `nifi_state=running` and `nifi_health=starting`           |
| `container.log`       | `wrong-host`: `APPLICATION FAILED TO START` and `Consul app java process has terminated prematurely`. `no-response`: `Checking if consul app main execution completed`, repeated, and no error |
| `nifi-app.log.gz`     | A single line saying that `nifi-app.log` does not exist: NiFi itself never started                         |
| `scenario-consul.txt` | `CONSUL_URL` of the NiFi container, the state of the Consul container, the startup errors, and for `no-response` the stack of the main thread of the Consul integration application |

## Remediation

1. Check `CONSUL_URL`. With `NIFI_CONSUL_INT_FRAMEWORK=spring` it is `<host>:<port>`; with `quarkus` it includes
   the protocol, `<protocol>://<host>:<port>`.
2. Check that the Consul host resolves and accepts requests from the NiFi pod: DNS, network policies, and the state
   of Consul itself.
3. Check that `CONSUL_ACL_TOKEN` matches the Consul instance.
4. Restart NiFi after a failed start. A hung startup continues by itself once Consul responds to the pending
   request: in the scenario, `docker unpause ts-consul` lets NiFi become healthy. The request has no timeout, so a
   response that never comes keeps the startup waiting until NiFi is restarted.
