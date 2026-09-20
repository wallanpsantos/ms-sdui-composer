package br.com.empresa.sdui.adapters.mongo.tx

import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import org.springframework.transaction.support.TransactionTemplate

class MongoTransactionalUnitOfWork(
    private val transactionTemplate: TransactionTemplate,
) : TransactionalUnitOfWork {
    override fun <T : Any> execute(work: () -> T): T =
        transactionTemplate.execute { work() }
            ?: error("TransactionTemplate retornou null para um trabalho nao-nulo")
}
