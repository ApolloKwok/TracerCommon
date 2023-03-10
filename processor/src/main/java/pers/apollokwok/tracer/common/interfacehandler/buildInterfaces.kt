package pers.apollokwok.tracer.common.interfacehandler

import com.google.devtools.ksp.findActualType
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.*
import pers.apollokwok.ksputil.Environment
import pers.apollokwok.ksputil.createFile
import pers.apollokwok.ksputil.qualifiedName
import pers.apollokwok.ksputil.resolver
import pers.apollokwok.ktutil.Bug
import pers.apollokwok.tracer.common.annotations.TracerInterface
import pers.apollokwok.tracer.common.shared.*
import pers.apollokwok.tracer.common.shared.Tags.AllInternal
import pers.apollokwok.tracer.common.util.*

private tailrec fun getSuperRootOrNodeKlass(klass: KSClassDeclaration): KSClassDeclaration?{
    val superKlass = klass.superTypes
        .map(KSTypeReference::resolve)
        .filterNot(KSType::isError)
        .map { it.declaration }
        .map {
            when(it){
                is KSClassDeclaration -> it
                is KSTypeAlias -> it.findActualType()
                else -> Bug()
            }
        }
        .firstOrNull { it.classKind == ClassKind.CLASS }

    return when{
        superKlass == null -> null

        // is Root/Nodes and its tracer interface is visible
        superKlass.isAnnotatedRootOrNodes()
        &&(superKlass.isNative()
            || "${Names.GENERATED_PACKAGE}.${getInterfaceNames(superKlass).first}"
            .let(resolver::getClassDeclarationByName)!!
            .isPublic()
        ) ->
            superKlass

        else -> getSuperRootOrNodeKlass(superKlass)
    }
}

internal fun buildInterface(klass: KSClassDeclaration) {
    // for passing the super context to child classes.
    val superRootOrNodeKlass: KSClassDeclaration? = getSuperRootOrNodeKlass(klass)

    val superName = superRootOrNodeKlass?.contractedName
    val superTracerName = superName?.plus(Names.Tracer)

    val context = klass.context
    val contextName = context?.contractedName
    val contextTracerName = contextName?.let { "${Names.OUTER}${it}Tracer" }

    val (implementsPart, outerImplementsPart) =
        when{
            superTracerName != null && contextTracerName != null ->
                " : $superTracerName, $contextTracerName" to
                " : ${Names.OUTER}$superTracerName, $contextTracerName"

            superTracerName != null ->
                " : $superTracerName" to
                " : ${Names.OUTER}$superTracerName"

            contextTracerName != null ->
                " : $contextTracerName" to
                " : $contextTracerName"

            else -> "" to ""
        }

    val grandpaContext = context?.context

    // each class may miss its import
    val imports = listOfNotNull(klass, superRootOrNodeKlass, grandpaContext)
        .map { it.topParentDecl.qualifiedName()!! }
        .associateBy { it.substringAfterLast(".") }
        .toMutableMap()
        // TracerInterface must has its import.
        .also {
            it[Names.TracerInterface] = TracerInterface::class.qualifiedName!!
        }
        .values
        .sorted()

    val contractedName = klass.contractedName

    val declPart = "val `_$contractedName`: ${klass.starTypeContent(imports)}"
    val outerDeclPart = declPart.replaceFirst("`", "`_")

    val superDeclPart =
        when{
            superRootOrNodeKlass == null
            // this being overridden property is already overridden.
            || superRootOrNodeKlass.isMyOpen() -> null

            superRootOrNodeKlass.isMyAbstract() ->
                "override val `_$superName`: ${superRootOrNodeKlass.starTypeContent(imports)} " +
                    "get() = `_$contractedName`"

            else -> Bug()
        }

    val outerSuperDeclPart = superRootOrNodeKlass?.let {
        "override val `__$superName`: ${it.starTypeContent(imports)} get() = `__$contractedName`"
    }

    val grandpaContextDeclPart = grandpaContext?.contractedName?.let { grandpaContextName ->
        "override val `__$grandpaContextName`: ${grandpaContext.starTypeContent(imports)} " +
            "get() = `__${contextName!!}`.`__$grandpaContextName`"
    }

    fun String?.onNewLineIfNotNull() =
        if (this == null)
            ""
        else
            "\n    $this"

    val (interfaceName, outerInterfaceName) = getInterfaceNames(klass)

    val visibilityPart =
        if (AllInternal)
            Visibility.INTERNAL.name.lowercase()
        else
            klass.moduleVisibility()!!.name.lowercase()

    val content =
        """
        |$SUPPRESSING
        |
        |package ${Names.GENERATED_PACKAGE}
        |
        |${imports.joinToString("\n") { "import $it" }}
        |
        |@${Names.TracerInterface}
        |$visibilityPart interface $interfaceName$implementsPart{
        |    $declPart ${superDeclPart.onNewLineIfNotNull()} ${grandpaContextDeclPart.onNewLineIfNotNull()}
        |}
        |
        |@${Names.TracerInterface}
        |$visibilityPart interface $outerInterfaceName$outerImplementsPart{
        |    $outerDeclPart ${outerSuperDeclPart.onNewLineIfNotNull()} ${grandpaContextDeclPart.onNewLineIfNotNull()}
        |}
        """.trimMargin()

    Environment.codeGenerator.createFile(
        packageName = Names.GENERATED_PACKAGE,
        fileName = interfaceName,
        dependencies = Dependencies(false, klass.containingFile!!),
        content = content,
    )
}