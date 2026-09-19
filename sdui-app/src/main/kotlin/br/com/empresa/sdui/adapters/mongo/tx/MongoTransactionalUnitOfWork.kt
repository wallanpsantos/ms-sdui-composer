package br.com.empresa.sdui.adapters.mongo.tx

import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import org.springframework.transaction.support.TransactionTemplate

class MongoTransactionalUnitOfWork(
    private val transactionTemplate: TransactionTemplate? = null,
) : TransactionalUnitOfWork {
    override fun <T> execute(work: () -> T): T {
        val template = transactionTemplate ?: return work()
        return template.execute { work() }
            ?: error("TransactionTemplate retornou null para um trabalho não-nulo")
    }
}
