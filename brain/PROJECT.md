# Project

Name: **dependency-analyze-kit**

Purpose: Maven plugin that finds unused dependencies in Spring Boot projects using Spring's condition evaluation report

## Scope

- User and problem: TODO
- MVP: TODO
- Acceptance criteria: TODO
- Out of scope: TODO

## Constraints

- Public by default only after a secret, copyright, data, and license check.
- Favor stdlib/native and the fewest safe dependencies.
- Prefer runnable examples, explicit failures, evidence, and simple maintenance.

See [[STATE]] and [[DECISIONS]].

## Found in the existing repo

- Candidate checks (unverified; confirm, then edit): `cd dependency-analyze-kit-core && mvn test`, `cd dependency-analyze-kit-gradle-plugin && mvn test`, `cd dependency-analyze-kit-maven-plugin && mvn test`, `cd dependency-analyze-kit-spring-probe && mvn test`, `mvn test`
- CI workflows (the commands CI really runs): `.github/workflows/build.yml`, `.github/workflows/release.yml`
- Existing guidance to follow first: none
