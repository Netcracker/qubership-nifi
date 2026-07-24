# Mock Testing NiFi Components

How to test NiFi components depending on what they are and what they
depend on: a plain Processor, a Processor with a Controller Service
dependency, a Controller Service itself, or a Reporting Task.

## Processor with no external dependencies

Use `TestRunner` directly - no mocking needed.

```java
private TestRunner testRunner;

@BeforeEach
public void init() {
    testRunner = TestRunners.newTestRunner(new MyProcessor());
}

@Test
public void testX() {
    testRunner.setProperty(MyProcessor.EXAMPLE_PROPERTY, "example-value");
    testRunner.enqueue("", attrs);
    testRunner.run();
    List<MockFlowFile> res = testRunner.getFlowFilesForRelationship(MyProcessor.REL_SUCCESS);
}
```

## Processor depending on a Controller Service

Write a minimal test implementation for the controller service interface
and register it on the same `TestRunner`:

```java
private class DBCPServiceSimpleImpl extends AbstractControllerService implements DBCPService {
    @Override
    public String getIdentifier() {
        return "dbcp";
    }

    @Override
    public Connection getConnection() throws ProcessException {
        return connection; // real or in-memory test connection
    }
}

@BeforeEach
public void init() throws InitializationException {
    DBCPService dbcp = new DBCPServiceSimpleImpl();
    testRunner.addControllerService("dbcp", dbcp, new HashMap<>());
    testRunner.setProperty(DBCP_SERVICE, "dbcp");
    testRunner.enableControllerService(dbcp);
}
```

## Controller Service tested directly

`TestRunner` only hosts Processors and Controller Services, not a bare
service. Create a minimal no-op host processor exposing one
`PropertyDescriptor` that `.identifiesControllerService(...)`, then drive
the service under test through that runner:

```java
public class TestProcessorHost extends AbstractProcessor {
    public static final PropertyDescriptor SERVICE = new PropertyDescriptor.Builder()
            .name("service")
            .identifiesControllerService(MyControllerService.class)
            .required(true)
            .build();

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) { }
}

@BeforeEach
public void setup() throws Exception {
    service = new MockMyControllerServiceImpl();
    runner = TestRunners.newTestRunner(TestProcessorHost.class);
    runner.addControllerService(service.getClass().getSimpleName(), service);
    runner.setProperty(service, MyControllerServiceImpl.EXAMPLE_PROPERTY, "example-value");
    runner.assertValid(service);
    runner.enableControllerService(service);
}
```

## Reporting Task

`ReportingTask` is not hosted by `TestRunner`. Mock the framework contracts,
but reuse NiFi's own `Mock*` utilities instead of hand-writing them:

```java
ReportingContext reportingContext = mock(ReportingContext.class);
EventAccess eventAccess = mock(EventAccess.class);
MockBulletinRepository mockBulletinRepository = mock(MockBulletinRepository.class);
when(reportingContext.getEventAccess()).thenReturn(eventAccess);
when(eventAccess.getControllerStatus()).thenReturn(processGroupStatus);

MockComponentLog componentLogger = new MockComponentLog("reporting-task-id", task);
ConfigurationContext configurationContext = new MockConfigurationContext(properties, null, null);
task.initialize(new MockReportingInitializationContext());
task.onScheduled(configurationContext);
```

Populate plain status DTOs (`ProcessGroupStatus`, `ProcessorStatus`,
`ConnectionStatus`) directly - they are data holders, not framework
contracts, so mocking them adds no value.

## Rules

- Processor with no dependencies: `TestRunners.newTestRunner(new X())` + `MockFlowFile`, no mocking.
- Processor depending on a Controller Service: write a small hand-rolled fake implementing the service interface, register/enable it via `addControllerService`/`enableControllerService` on the same `TestRunner` - do not mock `ProcessSession`/`ProcessContext` by hand.
- Controller Service tested standalone: host it on a minimal no-op processor whose only job is a `PropertyDescriptor` with `.identifiesControllerService(...)`, then drive lifecycle through that `TestRunner`.
- Reporting Task: mock framework contracts (`ReportingContext`, `EventAccess`); reuse NiFi's own `Mock*` classes (`MockComponentLog`, `MockConfigurationContext`, `MockReportingInitializationContext`, `MockBulletinRepository`) instead of writing new fakes for them.
- Never mock plain data/status objects (`ProcessGroupStatus`, `ProcessorStatus`, `ConnectionStatus`) - construct and populate them directly.
