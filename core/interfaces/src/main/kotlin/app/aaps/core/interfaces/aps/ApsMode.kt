package app.aaps.core.interfaces.aps

enum class ApsMode {
    OPEN, //开环
    CLOSED, //闭环
    LGS, //低血糖维持
    UNDEFINED; //未知的

    companion object {

        fun fromString(stringValue: String?) = values().firstOrNull { it.name == stringValue?.uppercase() } ?: UNDEFINED
    }
}
