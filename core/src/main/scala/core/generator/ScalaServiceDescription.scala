package core.generator

import com.gilt.apidocgenerator.models._
import core._
import Text._

object ScalaUtil {

  private val Keywords = Seq("case", "catch", "class", "def", "do",
                             "else", "extends", "false", "final",
                             "finally", "for", "forSome", "if",
                             "implicit", "import", "lazy", "match",
                             "new", "null", "object", "override",
                             "package", "private", "protected",
                             "return", "sealed", "super", "this",
                             "throw", "trait", "try", "true",
                             "type", "val", "var", "while",
                             "with", "yield").toSet

  def textToComment(text: String) = {
    if (text.trim.isEmpty) {
      ""
    } else {
      "/**\n * " + GeneratorUtil.splitIntoLines(text).mkString("\n * ") + "\n */"
    }
  }

  def fieldsToArgList(fields: Seq[String]): Option[String] = {
    if (fields.isEmpty) {
      None
    } else {
      Some(fields.map(_.indent).mkString("\n", ",\n", "\n"))
    }
  }

  def quoteNameIfKeyword(name: String): String = {
    if (Keywords.contains(name)) {
      "`" + name + "`"
    } else {
      name
    }
  }

  def packageName(serviceName: String): String = {
    Text.safeName(serviceName).toLowerCase
  }

  def toClassName(
    name: String,
    multiple: Boolean = false
  ) = {
    val baseName = Text.safeName(
      if (name == name.toUpperCase) {
        Text.initCap(Text.splitIntoWords(name).map(_.toLowerCase)).mkString("")
      } else {
        Text.initCap(snakeToCamelCase(name))
      }
    )

    ScalaUtil.quoteNameIfKeyword(
      if (multiple) {
        Text.pluralize(baseName)
      } else {
        baseName
      }
    )

  }

  def toVariable(
    name: String,
    multiple: Boolean = false
  ): String = {
    Text.initLowerCase(toClassName(name, multiple))
  }

}

class ScalaServiceDescription(val serviceDescription: ServiceDescription) {

  val name = ScalaUtil.toClassName(serviceDescription.name)

  val packageName: String = serviceDescription.packageName match {
    case None => ScalaUtil.packageName(serviceDescription.name)
    case Some(name) => name + "." + ScalaUtil.packageName(serviceDescription.name)
  }

  val modelPackageName = s"$packageName.models"
  val enumPackageName = modelPackageName

  def modelClassName(name: String) = modelPackageName + "." + ScalaUtil.toClassName(name)
  def enumClassName(name: String) = enumPackageName + "." + ScalaUtil.toClassName(name)

  val models = serviceDescription.models.map { new ScalaModel(this, _) }

  val enums = serviceDescription.enums.map { new ScalaEnum(_) }

  val packageNamePrivate = packageName.split("\\.").last

  val defaultHeaders: Seq[ScalaHeader] = {
    serviceDescription.headers.filter(!_.default.isEmpty).map { h =>
      ScalaHeader(h.name, h.default.get)
    }
  }

  val resources = serviceDescription.resources.map { new ScalaResource(this, _) }

}

case class ScalaHeader(name: String, value: String) {
  val quotedValue = s""""$value""""
}


class ScalaModel(val ssd: ScalaServiceDescription, val model: Model) {

  val name: String = ScalaUtil.toClassName(model.name)

  val plural: String = underscoreAndDashToInitCap(model.plural)

  val description: Option[String] = model.description

  val fields = model.fields.map { f => new ScalaField(ssd, this.name, f) }.toList

  val argList: Option[String] = ScalaUtil.fieldsToArgList(fields.map(_.definition))

}

class ScalaBody(val body: TypeInstance) {

  val name: String = body match {
    case TypeInstance(TypeContainer.Singleton, Type.Primitive(_)) => ScalaUtil.toClassName("value")
    case TypeInstance(TypeContainer.List | TypeContainer.Map, Type.Primitive(_)) => ScalaUtil.toClassName("values")

    case TypeInstance(TypeContainer.Singleton, Type.Model(name)) => ScalaUtil.toClassName(name)
    case TypeInstance(TypeContainer.List | TypeContainer.Map, Type.Model(name)) => ScalaUtil.toClassName(name, true)

    case TypeInstance(TypeContainer.Singleton, Type.Enum(name)) => ScalaUtil.toClassName(name)
    case TypeInstance(TypeContainer.List | TypeContainer.Map, Type.Enum(name)) => ScalaUtil.toClassName(name, true)
  }

}

class ScalaEnum(val enum: Enum) {

  val name: String = ScalaUtil.toClassName(enum.name)

  val description: Option[String] = enum.description

  val values: Seq[ScalaEnumValue] = enum.values.map { new ScalaEnumValue(_) }

}

class ScalaEnumValue(value: EnumValue) {

  val originalName: String = value.name

  val name: String = ScalaUtil.toClassName(value.name)

  val description: Option[String] = value.description

}

class ScalaResource(ssd: ScalaServiceDescription, resource: Resource) {
  val model = new ScalaModel(ssd, resource.model)

  val packageName: String = ssd.packageName

  val path = resource.path

  val operations = resource.operations.map { op =>
    new ScalaOperation(ssd, model, op, this)
  }
}

class ScalaOperation(ssd: ScalaServiceDescription, model: ScalaModel, operation: Operation, resource: ScalaResource) {

  val method: String = operation.method

  val path: String = operation.path

  val description: Option[String] = operation.description

  val body: Option[ScalaBody] = operation.body.map(new ScalaBody(_))

  val parameters: List[ScalaParameter] = {
    operation.parameters.toList.map { new ScalaParameter(ssd, _) }
  }

  lazy val pathParameters = parameters.filter { _.location == ParameterLocation.Path }

  lazy val queryParameters = parameters.filter { _.location == ParameterLocation.Query }

  lazy val formParameters = parameters.filter { _.location == ParameterLocation.Form }

  val name: String = GeneratorUtil.urlToMethodName(resource.model.plural, resource.path, operation.method, operation.path)

  val argList: Option[String] = operation.body match {
    case None => ScalaUtil.fieldsToArgList(parameters.map(_.definition))
    case Some(typeInstance) => {
      val sdt = ScalaDataType(typeInstance)

      Some(
        Seq(
          Some(s"%s: %s".format(std.varName, sdt.name)),
          ScalaUtil.fieldsToArgList(parameters.map(_.definition))
        ).flatten.mkString(", ")
      )
    }
    case Some(Type(TypeKind.Model, name, multiple)) => Some(bodyClassArg(name, multiple))
    case Some(Type(TypeKind.Enum, name, multiple)) => Some(bodyClassArg(name, multiple))
    case _ => sys.error(s"Invalid body [${operation.body}]")
  }

  private def bodyClassArg(
    name: String,
    multiple: Boolean
  ): String = {
    val baseClassName = ssd.modelClassName(name)
    val className = if (multiple) {
      s"scala.collection.Seq[$baseClassName]"
    } else {
      baseClassName
    }

    Seq(
      Some(s"${ScalaUtil.toVariable(name, multiple)}: $className"),
      ScalaUtil.fieldsToArgList(parameters.map(_.definition))
    ).flatten.mkString(", ")
  }

  val responses: Seq[ScalaResponse] = {
    operation.responses.toList.map { new ScalaResponse(ssd, method, _) } 
  }

  lazy val resultType = responses.find(_.isSuccess).map(_.resultType).getOrElse("Unit")

}

class ScalaResponse(ssd: ScalaServiceDescription, method: String, response: Response) {

  val scalaType: String = underscoreAndDashToInitCap(response.datatype.name)
  val isSingleton = response.`type`.container match {
    case TypeContainer.Singleton => true
    case TypeContainer.List | TypeContainer.Map => false
  }
  val isOption = isSingleton && !Util.isJsonDocumentMethod(method)

  val code = response.code
  val isSuccess = code >= 200 && code < 300
  val isNotFound = code == 404

  val datatype = ScalaDataType(response.`type`)

  val isUnit = datatype == ScalaDataType(Primitives.Unit)

  val resultType: String = datatype.name

  val errorVariableName = ScalaUtil.toVariable(scalaType, !isSingleton)

  val errorClassName = Text.initCap(errorVariableName) + "Response"

  val errorResponseType = datatype match {
    case ScalaDataType(ScalaDataType.OptionType(name)) => {
      // In the case of errors, ignore the option wrapper as we only
      // trigger the error response when we have an actual error.
      name
    }
    case _ => datatype.name
  }

}

class ScalaField(ssd: ScalaServiceDescription, modelName: String, field: Field) {

  def name: String = ScalaUtil.quoteNameIfKeyword(snakeToCamelCase(field.name))

  def originalName: String = field.name

  import ScalaDataType._

  /*
  val baseType: ScalaDataType = field.`type` match {
    case Type(TypeKind.Primitive, name, _) => ScalaDataType(Datatype.forceByName(name))
    case Type(TypeKind.Model, name, _) => new ScalaModelType(ssd.modelPackageName, name)
    case Type(TypeKind.Enum, name, _) => new ScalaEnumType(ssd.enumPackageName, name)
  }
   */

  def datatype = ScalaDataType(field.`type`)

  def description: Option[String] = field.description

  /**
   * If there is a default, ensure it is only set server side otherwise
   * changing the default would have no impact on deployed clients
   */
  def isOption: Boolean = !field.required || field.default.nonEmpty

  def definition: String = datatype.definition(name, isOption)
}

class ScalaParameter(ssd: ScalaServiceDescription, param: Parameter) {

  def name: String = ScalaUtil.toVariable(param.name)

  def originalName: String = param.name

  def datatype = ScalaDataType(param.`type`)
  def description: String = param.description.getOrElse(name)

  /**
   * If there is a default, ensure it is only set server side otherwise
   * changing the default would have no impact on deployed clients
   */
  def isOption: Boolean = !param.required || param.default.nonEmpty

  def definition: String = datatype.definition(name, isOption)

  def location = param.location
}

sealed abstract class ScalaDataType(val name: String) {

  def definition(
    varName: String,
    optional: Boolean
  ): String = {
    val decl = s"$varName: $name"
    if (optional) {
      field.`type`.container match {
        case TypeContainer.Singleton => decl + " = None"
        case TypeContainer.List => decl + " = Nil"
        case TypeContainer.Map => decl + " = Map.Empty"
      }
    } else {
      decl
    }
  }

}

object ScalaDataType {

  case object ScalaStringType extends ScalaDataType("String")
  case object ScalaIntegerType extends ScalaDataType("Int")
  case object ScalaDoubleType extends ScalaDataType("Double")
  case object ScalaLongType extends ScalaDataType("Long")
  case object ScalaBooleanType extends ScalaDataType("Boolean")
  case object ScalaDecimalType extends ScalaDataType("BigDecimal")
  case object ScalaUnitType extends ScalaDataType("Unit")

  case object ScalaUuidType extends ScalaDataType("_root_.java.util.UUID")
  case object ScalaDateIso8601Type extends ScalaDataType("_root_.org.joda.time.LocalDate")
  case object ScalaDateTimeIso8601Type extends ScalaDataType("_root_.org.joda.time.DateTime")

  case class ScalaModelType(packageName: String, modelName: String) extends ScalaDataType(s"${packageName}.${ScalaUtil.toClassName(modelName)}")
  case class ScalaEnumType(packageName: String, enumName: String) extends ScalaDataType(s"${packageName}.${ScalaUtil.toClassName(enumName)}")
  case class ScalaListType(inner: ScalaDataType) extends ScalaDataType(s"scala.collection.Seq[${inner.name}]")
  case class ScalaMapType(inner: ScalaDataType) extends ScalaDataType(s"scala.collection.Map[String, ${inner.name}]")
  case class ScalaOptionType(inner: ScalaDataType) extends ScalaDataType(s"scala.Option[${inner.name}]")

  def apply(typeInstance: TypeInstance): ScalaDataType = typeInstance match {
    case TypeInstance(TypeContainer.Singleton, Type.Primitive(pt)) => ScalaDataType(pt)
    case TypeInstance(TypeContainer.List, Type.Primitive(pt)) => ScalaListType(ScalaDataType(pt))
    case TypeInstance(TypeContainer.Map, Type.Primitive(pt)) => ScalaMapType(ScalaDataType(pt))

    case TypeInstance(TypeContainer.Singleton, Type.Model(name)) => ScalaModelType(name)
    case TypeInstance(TypeContainer.List, Type.Model(name)) => ScalaListType(ScalaModelType(name))
    case TypeInstance(TypeContainer.Map, Type.Model(name)) => ScalaMapType(ScalaModelType(name))

    case TypeInstance(TypeContainer.Singleton, Type.Enum(name)) => ScalaEnumType(name)
    case TypeInstance(TypeContainer.List, Type.Enum(name)) => ScalaListType(ScalaEnumType(name))
    case TypeInstance(TypeContainer.Map, Type.Enum(name)) => ScalaMapType(ScalaEnumType(name))
  }

  def asString(varName: String, d: ScalaDataType): String = d match {
    case ScalaStringType => s"$varName"
    case ScalaIntegerType => s"$varName.toString"
    case ScalaDoubleType => s"$varName.toString"
    case ScalaLongType => s"$varName.toString"
    case ScalaBooleanType => s"$varName.toString"
    case ScalaDecimalType => s"$varName.toString"
    case ScalaUuidType => s"$varName.toString"
    case ScalaDateIso8601Type => s"$varName.toString"
    case ScalaDateTimeIso8601Type => {
      s"org.joda.time.format.ISODateTimeFormat.dateTime.print($varName)"
    }
    case ScalaEnumType(_, _) => s"$varName.toString"
    case _ => throw new UnsupportedOperationException(s"unsupported conversion of type ${d} to query string for $varName")
  }

}
