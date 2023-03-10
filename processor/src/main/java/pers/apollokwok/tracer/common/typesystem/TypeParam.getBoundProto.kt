package pers.apollokwok.tracer.common.typesystem

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSTypeParameter
import pers.apollokwok.ksputil.simpleName

internal fun KSTypeParameter.getBoundProto(): Type<*> =
    when(bounds.count()){
        0 -> Type.`Any？`

        1 -> {
            // check recycle
            val boundType = bounds.first().resolve()
            val boundInnerParam = boundType.arguments.firstOrNull()?.type?.resolve()?.declaration as? KSTypeParameter
            if (boundInnerParam?.simpleName() == this.simpleName())
                Type.Specific(
                    decl = boundType.declaration as KSClassDeclaration,
                    // I forget what this line means
                    // todo: consider about changing another arg, but Arg structure may change if so.
                    args = listOf(Arg.Star(this)),
                    genericName = null,
                    isNullable = boundType.isMarkedNullable,
                    hasAlias = false,
                    hasConvertibleStar = false,
                )
            else
                bounds.first().toProto()
        }

        else -> {
            val originalTypes = bounds.map { it.toProto() }.toList()

            Type.Compound(
                types = originalTypes.map { it.updateNullability(false) },
                isNullable = originalTypes.all { it.isNullable }
            )
        }
    }