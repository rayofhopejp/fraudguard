package com.fraudguard.monitor.data.local

import androidx.room.TypeConverter
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel

class Converters {
    @TypeConverter
    fun fromEventType(value: EventType): String = value.name

    @TypeConverter
    fun toEventType(value: String): EventType = EventType.valueOf(value)

    @TypeConverter
    fun fromRiskLevel(value: RiskLevel): String = value.name

    @TypeConverter
    fun toRiskLevel(value: String): RiskLevel = RiskLevel.valueOf(value)
}
