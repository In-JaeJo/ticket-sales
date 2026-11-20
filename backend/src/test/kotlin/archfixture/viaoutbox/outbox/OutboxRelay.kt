package archfixture.viaoutbox.outbox

import archfixture.viaoutbox.payment.PaymentResult

/** 위반: 인프라(outbox)가 도메인(payment)을 참조한다. */
class OutboxRelay {
    fun lastPaid(): Boolean = PaymentResult(true).ok
}
