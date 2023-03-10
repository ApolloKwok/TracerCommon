package pers.apollokwok.tracer.common.prophandler

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Visibility
import pers.apollokwok.ktutil.lazyFast
import pers.apollokwok.ktutil.updateIf
import pers.apollokwok.tracer.common.shared.Tags
import pers.apollokwok.tracer.common.shared.contractedName
import pers.apollokwok.tracer.common.shared.getInterfaceNames
import pers.apollokwok.tracer.common.shared.topParentDecl
import pers.apollokwok.tracer.common.typesystem.Type
import pers.apollokwok.tracer.common.typesystem.getTraceableTypes
import pers.apollokwok.tracer.common.util.filterOutRepeated
import pers.apollokwok.tracer.common.util.isCommon
import pers.apollokwok.tracer.common.util.isFinal

internal sealed class PropInfo(
    val type: Type<*>,
    v: Visibility,
    propsBuilder: PropsBuilder,
){
    companion object{
        // process props, make some declaredWithOwnerName or declaredWithPropName further.
        internal fun Collection<PropInfo>.process(){
            if (Tags.PropertiesFullName) return

            filterOutRepeated{ it.grossKey }
            .forEach { it.ownerNameContained = true }

            // concise though a little time-consuming
            filterIsInstance<FromElement>()
            .filterOutRepeated{ it.grossKey to it.prop.parentDeclaration }
            .forEach { it.propNameContained = true }
        }
    }

    protected var ownerNameContained = Tags.PropertiesFullName || type.isCommon()

    private val typeContent: String? by lazyFast {
        type.getContent(getPathImported = { it.topParentDecl in propsBuilder.importedTopKlasses })
    }

    val grossKey: String by lazyFast {
        type.getName(isGross = true, getSrcTag = propsBuilder.srcTags::get)
    }

    private val propInfoNameCore: String by lazyFast {
        type.getName(false, propsBuilder.srcTags::get)
            .updateIf(
                predicate = {
                    typeContent == null
                    && when (this@PropInfo) {
                        is FromSrcKlassSuper -> true
                        is FromElement -> type != prop.getTraceableTypes().first()
                    }
                },
                update = { "$it??" }
            )
    }

    private val sourceKlass = propsBuilder.sourceKlass
    private val levelTag = "??${sourceKlass.contractedName}"

    // Here needn't consider about packageNameTag because it's owned only by other-module
    // declarations.
    private val propInfoNames: List<String> by lazyFast {
        arrayOf(false, true).map { isOuter ->
            buildString {
                // add `` on both sides in case users use special characters themselves.
                append("`_")
                if (isOuter) append("_")
                append(propInfoNameCore)

                if (!sourceKlass.isFinal() || isOuter)
                    append("_$levelTag")

                when(this@PropInfo){
                    is FromSrcKlassSuper ->
                        if (ownerNameContained)
                            append("_by${klass.contractedName}")

                    is FromElement -> {
                        if (ownerNameContained)
                            append("_${prop.parentDeclaration!!.contractedName}")

                        if (propNameContained)
                            append("_$prop")
                    }
                }

                append("`")
            }
        }
    }

    private val references: List<String> by lazyFast {
        arrayOf(false, true).map { isOuter ->
            buildString {
                append("`_")
                if (isOuter) append("_")

                when(this@PropInfo) {
                    is FromSrcKlassSuper -> append("${klass.contractedName}`")

                    is FromElement ->
                        // properties in sourceKlass
                        if (parentProp == null)
                            append("${sourceKlass.contractedName}`.`$prop`")

                        // below are properties in general classes
                        else {
                            append(prop.parentDeclaration!!.contractedName)

                            if (!sourceKlass.isFinal() || isOuter)
                                append("_$levelTag")

                            if (Tags.PropertiesFullName)
                                append("_${parentProp.parentDeclaration!!.contractedName}_$parentProp")

                            append("`.`$prop`")
                        }
                }
            }
        }
    }

    private val declContents: List<String> by lazyFast{
        (0..1).map { i ->
            val interfaceName = getInterfaceNames(sourceKlass).toList()[i]
            val typePart = typeContent?.let { "as $it" } ?: ""
            "${v.name.lowercase()} val $interfaceName.${propInfoNames[i]} inline get() = ${references[i]} $typePart"
        }
    }
    val declContent by lazyFast { declContents[0] }
    val outerDeclContent by lazyFast { declContents[1] }

    class FromElement(
        val prop: KSPropertyDeclaration,
        val parentProp:  KSPropertyDeclaration?,
        type: Type<*>,
        v: Visibility,
        propsBuilder: PropsBuilder,
    ) :
        PropInfo(type, v, propsBuilder)
    {
        var propNameContained = ownerNameContained
    }

    class FromSrcKlassSuper(
        val klass: KSClassDeclaration,
        type: Type<*>,
        v: Visibility,
        propsBuilder: PropsBuilder
    ) :
        PropInfo(type, v, propsBuilder)
}