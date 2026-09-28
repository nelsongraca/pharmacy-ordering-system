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
