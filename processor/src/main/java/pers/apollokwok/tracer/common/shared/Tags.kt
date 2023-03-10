package pers.apollokwok.tracer.common.shared

import pers.apollokwok.ksputil.Environment

public object Tags {
    public val AllInternal: Boolean = "tracer.allInternal" in Environment.options.keys
    public val PropertiesFullName: Boolean = "tracer.propertiesFullName" in Environment.options.keys
}