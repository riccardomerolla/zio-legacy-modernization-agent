package models

import java.time.Instant

import zio.json.*

enum HttpMethod:
  case GET, POST, PUT, DELETE, PATCH

object HttpMethod:
  private val known: Map[String, HttpMethod] = Map(
    "GET"    -> HttpMethod.GET,
    "POST"   -> HttpMethod.POST,
    "PUT"    -> HttpMethod.PUT,
    "DELETE" -> HttpMethod.DELETE,
    "PATCH"  -> HttpMethod.PATCH,
  )

  given JsonCodec[HttpMethod] = JsonCodec[String].transform(
    value => known.getOrElse(normalize(value), HttpMethod.GET),
    _.toString,
  )

  private def normalize(value: String): String =
    value.trim.toUpperCase.replaceAll("[^A-Z0-9]", "")

case class JavaPackage(
  name: String,
  classes: List[String],
) derives JsonCodec

case class JavaField(
  name: String,
  javaType: String,
  cobolSource: String,
  annotations: List[String],
) derives JsonCodec

case class JavaEntity(
  className: String,
  packageName: String,
  fields: List[JavaField],
  annotations: List[String],
  sourceCode: String,
) derives JsonCodec

case class JavaParameter(
  name: String,
  javaType: String,
) derives JsonCodec

case class JavaMethod(
  name: String,
  returnType: String,
  parameters: List[JavaParameter],
  body: String,
) derives JsonCodec

case class JavaService(
  name: String,
  methods: List[JavaMethod],
) derives JsonCodec

case class RestEndpoint(
  path: String,
  method: HttpMethod,
  methodName: String,
) derives JsonCodec

case class JavaController(
  name: String,
  basePath: String,
  endpoints: List[RestEndpoint],
) derives JsonCodec

case class JavaRepository(
  name: String,
  entityName: String,
  idType: String,
  packageName: String,
  annotations: List[String],
  sourceCode: String,
) derives JsonCodec

case class BuildFile(
  tool: String,
  content: String,
) derives JsonCodec

case class ProjectConfiguration(
  groupId: String,
  artifactId: String,
  dependencies: List[String],
) derives JsonCodec

case class SpringBootProject(
  projectName: String,
  sourceProgram: String,
  generatedAt: Instant,
  entities: List[JavaEntity],
  services: List[JavaService],
  controllers: List[JavaController],
  repositories: List[JavaRepository],
  configuration: ProjectConfiguration,
  buildFile: BuildFile,
) derives JsonCodec

object SpringBootProject:
  def empty: SpringBootProject = SpringBootProject(
    projectName = "",
    sourceProgram = "",
    generatedAt = Instant.EPOCH,
    entities = List.empty,
    services = List.empty,
    controllers = List.empty,
    repositories = List.empty,
    configuration = ProjectConfiguration("", "", List.empty),
    buildFile = BuildFile("maven", ""),
  )
