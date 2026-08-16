# Getting Started

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.0.7/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.0.7/maven-plugin/build-image.html)
* [Distributed Tracing Reference Guide](https://docs.micrometer.io/tracing/reference/index.html)
* [Getting Started with Distributed Tracing](https://docs.spring.io/spring-boot/4.0.7/reference/actuator/tracing.html)
* [Flyway Migration](https://docs.spring.io/spring-boot/4.0.7/how-to/data-initialization.html#howto.data-initialization.migration-tool.flyway)
* [HTTP Client](https://docs.spring.io/spring-boot/4.0.7/reference/io/rest-client.html#io.rest-client.restclient)
* [Spring Boot DevTools](https://docs.spring.io/spring-boot/4.0.7/reference/using/devtools.html)
* [Docker Compose Support](https://docs.spring.io/spring-boot/4.0.7/reference/features/dev-services.html#features.dev-services.docker-compose)
* [Spring Configuration Processor](https://docs.spring.io/spring-boot/4.0.7/specification/configuration-metadata/annotation-processor.html)
* [OpenTelemetry](https://docs.spring.io/spring-boot/4.0.7/reference/actuator/observability.html#actuator.observability.opentelemetry)
* [SpringDoc OpenAPI](https://springdoc.org/)

### Guides
The following guides illustrate how to use some features concretely:

* [SpringDoc OpenAPI](https://github.com/springdoc/springdoc-openapi-demos/)

### Docker Compose support
This project contains a Docker Compose file named `compose.yaml`.
In this file, the following services have been defined:

* grafana-lgtm: [`grafana/otel-lgtm:latest`](https://hub.docker.com/r/grafana/otel-lgtm)
* postgres: [`postgres:latest`](https://hub.docker.com/_/postgres)

Please review the tags of the used images and set them to the same as you're running in production.

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.

