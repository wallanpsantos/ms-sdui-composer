package br.com.empresa.sdui.adapters.mongo.tx

import br.com.empresa.sdui.orchestrator.port.outbound.TransactionalUnitOfWork
import org.springframework.transaction.support.TransactionTemplate

class MongoTransactionalUnitOfWork(
    private val transactionTemplate: TransactionTemplate,
) : TransactionalUnitOfWork {
    override fun <T : Any> execute(work: () -> T): T {
        // execute() devolve um platform type; sem a anotacao explicita de nulabilidade o compilador
        // trata o resultado como T nao-nulo e acusa o elvis como sempre-esquerdo.
        val result: T? = transactionTemplate.execute { work() }
        return result ?: error("TransactionTemplate retornou null para um trabalho nao-nulo")
    }
}
