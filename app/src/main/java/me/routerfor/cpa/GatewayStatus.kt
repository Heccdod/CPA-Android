package me.routerfor.cpa

/**
 * The four states the gateway can be in, mirroring the strings the Go side returns from
 * Status().
 *
 * The distinction that matters is STOPPED versus STARTING. Both are "not running and no error
 * message", and the home screen used to collapse them into 正在启动 — so a deliberate stop looked
 * like a gateway that was still coming up, and kept looking that way forever because nothing was
 * ever going to start it.
 */
object GatewayStatus {
    const val RUNNING = "running"
    const val STARTING = "starting"
    const val STOPPED = "stopped"
    const val ERROR = "error"
}
