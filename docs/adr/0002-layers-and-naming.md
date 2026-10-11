# ADR 0002: Layers, packages and naming

Date: 2026-10-11.
Status: accepted by the user for subsequent implementation. The full structure is not implemented; separate Maven modules have not been approved.

## Layer boundaries

- Controller calls only Service for business operations. It does not inject Repository/Mapper, execute SQL or return Entity objects. DTOs and input validation are Controller contracts and do not violate this rule.
- Service owns business operations and transaction boundaries; it uses Repository, Mapper and agreed adapter/event interfaces. It does not depend on Controller.
- Repository owns data access and does not depend on Controller, Service or Mapper.
- Mapper (MapStruct) converts models, does not call Service/Repository and contains no business operations. Helper mappers are allowed.
- Batch runners and event handlers call Service as inbound adapters. Add web controllers when HTTP APIs are introduced.

## Naming

- All persistence entity classes end in `Entity`: `VacancyEntity`, `EmployerEntity`, `AgentRunEntity`.
- All DTOs end in `DTO` (three uppercase letters): `VacancyDTO`, `CreateVacancyDTO`. This also applies to DTO records.
- Domain events name facts (`VacancyDiscovered`); an event is not automatically a DTO or persistence entity. Names follow model purpose.
- Other classes use clear role suffixes: `VacancyController`, `VacancyService`, `VacancyRepository`, `VacancyMapper`, `VacancyNotFoundException`, `GlobalExceptionHandler`, `MappingConfig`.

## Packages

Introduce layers within domain modules when actual code needs them:

```text
com.alexs.jobagent
  vacancy
    controller
    service
    repository
    mapper
    entity
    dto
    exception
    event
  run
    service
    repository
    entity
  exceptionhandler
  config
```

Exceptions live in a module's separate `exception` package; shared exceptions may use a root `exception` package. Handlers live in `exceptionhandler`; Spring configuration classes live in shared/module `config` packages. Introduce the shared HTTP handler with the HTTP API. Batch failures retain a nonzero exit code and safe diagnostics.

`application.properties`/`application.yaml` and Flyway SQL stay in standard resources paths: the `config` rule applies to Java configuration classes.

## Modularity and checks

Start with one Maven project and domain packages defined in `MASTER_PLAN.md`. Introduce separate Maven modules for a concrete need and record that decision. Cross-module calls use public service interfaces/event contracts, not another module's internal repositories.

As layers appear, add necessary architecture checks for forbidden dependencies (ArchUnit is a candidate, not yet configured). Do not create empty classes/packages to fill the diagram. Do not rename stable domain events as DTOs solely to impose a suffix.
