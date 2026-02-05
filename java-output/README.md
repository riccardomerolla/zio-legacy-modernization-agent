# Generated Spring Boot Projects

This directory contains the generated Spring Boot microservices created from COBOL programs.

## Output Structure

Each COBOL program is transformed into a separate Spring Boot project:

```
java-output/
├── customer-service/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/example/customer/
│   │   │   │       ├── CustomerController.java
│   │   │   │       ├── CustomerService.java
│   │   │   │       ├── CustomerRepository.java
│   │   │   │       └── model/
│   │   │   │           └── Customer.java
│   │   │   └── resources/
│   │   │       └── application.properties
│   │   └── test/
│   │       └── java/
│   ├── pom.xml
│   └── README.md
└── transformation-report.json
```

## Running Generated Services

Each service can be run independently:

```bash
cd java-output/customer-service
./mvnw spring-boot:run
```

## Testing

Run tests for a generated service:

```bash
cd java-output/customer-service
./mvnw test
```
