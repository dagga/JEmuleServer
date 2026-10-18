## JEmuleServer 1.2.2

This patch release fixes startup of the admin web panel on standard Java runtimes and in the bundled application
installers.

### Fixes

- Resolve the JDK `jdk.httpserver` module used by the loopback admin web panel when launching the executable JAR or
  running the Gradle application task.
- Include `jdk.httpserver` in the linked Java runtime bundled with Linux, Windows, and macOS installers.
- Correct the README launch command to use the current JAR filename and explicitly enable the required module.

### Compatibility

The eD2K and Kad protocol behavior is unchanged. Existing v1.2.1 JAR users can work around the startup error by running:

```bash
java --add-modules=jdk.httpserver -jar JEmuleServer-1.2.1.jar
```

The JAR requires Java 21 or later. Release installers bundle their Java runtime.
