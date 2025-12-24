# pluginloader

pluginloader is a Spring Boot starter that lets a host application pull feature code from other Git projects at build time and register the feature beans at runtime.

## How it works
- **Build time (annotation processor)**: When the host runs `mvn compile`, `PluginLoaderProcessor` reads `pluginloader.features` from `application.yml`, clones each configured repository/branch, imports `.java` sources into generated sources so they compile with the host, scans for packages and bean classes, optionally builds the feature jar for reference, and emits a descriptor class under `generated.<FeatureName>Descriptor` that lists packages and bean classes.
- **Runtime (auto-configuration)**: With `pluginloader.enabled=true`, `PluginloaderAutoConfiguration` runs `FeatureManager`, which loads the generated descriptors (or falls back to configured packages) and registers the discovered beans into the host context using the host classloader, no runtime cloning or building.

## Add to your project (Maven)
1. Dependency:
   ```xml
   <dependency>
     <groupId>com.aajumaharjan</groupId>
     <artifactId>pluginloader</artifactId>
     <version>0.0.28-SNAPSHOT</version>
   </dependency>
   ```
2. Annotation processor on the compile plugin:
   ```xml
   <plugin>
     <groupId>org.apache.maven.plugins</groupId>
     <artifactId>maven-compiler-plugin</artifactId>
     <configuration>
       <annotationProcessorPaths>
         <path>
           <groupId>com.aajumaharjan</groupId>
           <artifactId>pluginloader</artifactId>
           <version>0.0.28-SNAPSHOT</version>
         </path>
         <!-- your other processors, e.g., Lombok -->
       </annotationProcessorPaths>
     </configuration>
   </plugin>
   ```

## Configure features
In the host `src/main/resources/application.yml`:
```yaml
pluginloader:
  enabled: true
  features:
    - repository: https://github.com/your-org/feature-repo.git   # or file:///... for local
      branch: main                                              # optional, defaults to main
      packages:                                                 # optional filters; if omitted, all packages are considered
        - com.yourorg.feature.service
        - com.yourorg.feature.auth
```

## Using it
1. Run `mvn compile` in the host app. The processor clones the repositories, imports sources, and generates descriptors under `target/generated-sources/annotations`.
2. Start the host app. Auto-configuration creates a child context for each feature and registers beans (by package scan or explicit bean class names from descriptors) into the host context so they can be autowired.


## End-to-end example (host config → feature reads config → host uses feature bean)

This example shows:
1) the **host** enabling a feature via `application.yml`,
2) the **feature** reading configuration provided by the host, and
3) the **host** autowiring and using a bean contributed by the feature.

---

### 1) Host application config (`src/main/resources/application.yml`)

```yaml
pluginloader:
  enabled: true
  features:
    - repository: https://github.com/your-org/greeting-feature.git
      branch: main
      packages:
        - com.yourorg.greeting

# Host-owned configuration that the feature will read
greeting:
  message: "Hello from host config!"
  enabled: true
````

---

### 2) Feature project code (in `greeting-feature` repo)

#### 2.1 Feature configuration properties

```java
package com.yourorg.greeting;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "greeting")
public class GreetingProperties {
  private String message = "Hello (default)";
  private boolean enabled = true;

  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
```

#### 2.2 Feature service bean that uses the host-provided config

```java
package com.yourorg.greeting;

import org.springframework.stereotype.Service;

@Service
public class GreetingService {
  private final GreetingProperties props;

  public GreetingService(GreetingProperties props) {
    this.props = props;
  }

  public String greet(String name) {
    if (!props.isEnabled()) {
      return "Greeting feature is disabled";
    }
    return props.getMessage() + " Name=" + name;
  }
}
```

#### 2.3 Feature auto-configuration (recommended)

This ensures the `@ConfigurationProperties` is registered when the feature is loaded.

```java
package com.yourorg.greeting;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GreetingProperties.class)
public class GreetingFeatureConfiguration { }
```

> If your feature already relies on component scanning and the configuration class is in a scanned package, this will be picked up when the feature is registered.

---

### 3) Host application uses the feature bean

#### 3.1 Host app entry point

```java
package com.yourorg.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HostApplication {
  public static void main(String[] args) {
    SpringApplication.run(HostApplication.class, args);
  }
}
```

#### 3.2 Host controller autowires the feature service and uses it

```java
package com.yourorg.host;

import com.yourorg.greeting.GreetingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GreetingController {

  private final GreetingService greetingService;

  public GreetingController(GreetingService greetingService) {
    this.greetingService = greetingService;
  }

  @GetMapping("/greet")
  public String greet(@RequestParam(defaultValue = "Aaju") String name) {
    return greetingService.greet(name);
  }
}
```

---

### 4) Run it

1. In the host app:

   ```bash
   mvn compile
   ```

   This clones `greeting-feature`, imports sources, and generates descriptors.

2. Start the host:

   ```bash
   mvn spring-boot:run
   ```

3. Call the endpoint:

   * `GET /greet?name=Bob`
   * Response:

     * `Hello from host config! Name=Bob`

---


## Notes & tips
- Requires JDK 17+ and Maven available on PATH during compilation.
- Repositories must be reachable from the build machine; use `file:///...` URLs to work offline.
- The generated descriptor includes a JAR_PATH field for reference; current runtime registration uses host-visible classes and the packages/bean-class lists.
- Enable debug logging (`logging.level.com.aajumaharjan.pluginloader=DEBUG`) to see processor and runtime integration details.

## Design notes
- Purpose: provide a configuration-driven way to compose modular features from separate repositories into a single Spring Boot host, without manual code wiring.
- Problem addressed: conventional component scanning works well within one codebase but does not help when features live in other repos; aligning dependencies, classpaths, and bean registration across repo boundaries is otherwise manual and error-prone.
- Convention-over-configuration limits: once features are split across repos, relying on package scanning alone is insufficient, discovery must know where code resides and which parts to include. pluginloader externalises that selection to configuration.
- Configuration-driven rationale: feature selection (which repos/branches/packages) is declared in `application.yml`, making inclusion explicit, reviewable, and environment-specific without code changes.
- Build-time source integration rationale: the annotation processor clones repos and imports sources during compilation so classes are compiled with the host, using the host classloader at runtime. This avoids runtime classloader complexity while keeping the dependency surface explicit.
- Clarity and control: descriptors list packages and bean classes; runtime uses only host-visible classes. This improves predictability, makes failures earlier (compile-time), and keeps operational behaviour transparent.
- Not a hot-reload system: pluginloader does not hot-swap code, is not an OSGi alternative, and is not a microservices framework; it is a build-time integration plus runtime auto-configuration mechanism for modular features.
