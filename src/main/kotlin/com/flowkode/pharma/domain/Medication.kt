package com.flowkode.pharma.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name="medications")
class Medication : PanacheEntity() {

    companion object : PanacheCompanion<Medication> {

        /** Reserves stock atomically. False means not enough on hand (or unknown medication). */
        fun reserve(id: Long, quantity: Long): Boolean =
            update("reserved = reserved + ?1 where id = ?2 and stock - reserved >= ?1", quantity, id) == 1

        /** Returns previously reserved stock. */
        fun release(id: Long, quantity: Long) {
            update("reserved = reserved - ?1 where id = ?2", quantity, id)
        }
    }

    @Column(unique = true)
    lateinit var name: String

    var stock: Long = 0

    var reserved: Long = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Medication) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0


}
