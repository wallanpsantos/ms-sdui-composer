package br.com.empresa.sdui.adapters.mongo.tx

import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import org.springframework.transaction.annotation.Transactional

open class MongoTransactionalUnitOfWork : TransactionalUnitOfWork {
    @Transactional
    override fun <T> execute(work: () -> T): T = work()
}
