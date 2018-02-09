package net.corda.node.internal.artemis

import net.corda.node.internal.LifecycleSupport
import org.apache.activemq.artemis.api.core.client.ClientConsumer
import org.apache.activemq.artemis.api.core.client.ClientMessage
import org.apache.activemq.artemis.api.core.client.ClientSession
import rx.Observable
import rx.subjects.PublishSubject

interface ReactiveArtemisConsumer : LifecycleSupport {

    val messages: Observable<ClientMessage>

    fun reconnect()

    companion object {

        fun multiplex(createSession: () -> ClientSession, queueName: String, vararg queueNames: String): ReactiveArtemisConsumer {

            return MultiplexingReactiveArtemisConsumer(setOf(queueName, *queueNames), createSession)
        }

        fun multiplex(queueNames: Set<String>, createSession: () -> ClientSession): ReactiveArtemisConsumer {

            return MultiplexingReactiveArtemisConsumer(queueNames, createSession)
        }
    }
}

private class MultiplexingReactiveArtemisConsumer(private val queueNames: Set<String>, private val createSession: () -> ClientSession) : ReactiveArtemisConsumer {

    private var startedFlag = false

    override val messages: PublishSubject<ClientMessage> = PublishSubject.create<ClientMessage>()

    private val consumers = mutableSetOf<ClientConsumer>()
    private val sessions = mutableSetOf<ClientSession>()

    override fun start() {

        synchronized(this) {
            require(!startedFlag)
            connect()
            startedFlag = true
        }
    }

    override fun stop() {

        synchronized(this) {
            if(startedFlag) {
                disconnect()
                startedFlag = false
            }
            messages.onCompleted()
        }
    }

    override fun reconnect() {

        synchronized(this) {
            require(startedFlag)
            disconnect()
            connect()
        }
    }

    private fun connect() {

        queueNames.forEach { queue ->
            createSession().apply {
                start()
                consumers += createConsumer(queue)
                sessions += this
            }
        }
        consumers.forEach { consumer ->
            consumer.setMessageHandler { message ->
                messages.onNext(message)
            }
        }
    }

    private fun disconnect() {

        synchronized(this) {
            consumers.forEach(ClientConsumer::close)
            sessions.forEach(ClientSession::close)
            consumers.clear()
            sessions.clear()
        }
    }

    override val started: Boolean
        get() = startedFlag
}