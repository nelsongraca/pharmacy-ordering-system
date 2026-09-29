package com.flowkode.pharma.util

import io.quarkus.narayana.jta.QuarkusTransaction

// syntactic sugar I know
fun <T> transactional(fn: () -> T) = QuarkusTransaction.requiringNew().call { fn() }

