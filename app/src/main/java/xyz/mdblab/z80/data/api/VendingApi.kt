package xyz.mdblab.z80.data.api

import retrofit2.http.GET
import retrofit2.http.Path

interface VendingApi {

    // --- PAYMENTS ---
    @retrofit2.http.POST("raspberry-pay-requests")
    suspend fun createPayment(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @retrofit2.http.Body request: xyz.mdblab.z80.data.api.models.PaymentRequest
    ): xyz.mdblab.z80.data.api.models.PaymentResponse

    @retrofit2.http.POST("qr-pay-requests")
    suspend fun createQrPayment(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @retrofit2.http.Body request: xyz.mdblab.z80.data.api.models.QrPaymentRequest
    ): xyz.mdblab.z80.data.api.models.QrPaymentResponse

    @GET("qr-pay-requests/{id}")
    suspend fun getQrStatus(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @Path("id") id: String
    ): xyz.mdblab.z80.data.api.models.QrStatusResponse

    @retrofit2.http.PATCH("qr-pay-requests/{id}/cancel")
    suspend fun cancelQrPayment(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @Path("id") id: String
    ): xyz.mdblab.z80.data.api.models.QrStatusResponse

    @retrofit2.http.POST("heartbeat")
    suspend fun sendHeartbeat(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @retrofit2.http.Body payload: xyz.mdblab.z80.data.api.models.HeartbeatPayload
    ): xyz.mdblab.z80.data.api.models.HeartbeatResponse

    // --- CARD PAYMENT CANCEL ---
    @retrofit2.http.PATCH("raspberry-pay-requests/{id}/cancel")
    suspend fun cancelCardPayment(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @Path("id") id: String
    ): xyz.mdblab.z80.data.api.models.CardPaymentStatusResponse

    // --- CARD PAYMENT POLLING ---
    @GET("raspberry-pay-requests/{id}")
    suspend fun getCardPaymentStatus(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @Path("id") id: String
    ): xyz.mdblab.z80.data.api.models.CardPaymentStatusResponse

    // --- DISPENSE RESULT REPORTING ---
    @retrofit2.http.POST("pay-requests/{id}/dispense-result")
    suspend fun reportDispenseResult(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @Path("id") id: String,
        @retrofit2.http.Body request: xyz.mdblab.z80.data.api.models.DispenseResultRequest
    ): xyz.mdblab.z80.data.api.models.DispenseResultResponse

    @retrofit2.http.POST("qr-pay-requests/{qr_id}/dispense-result")
    suspend fun reportQrDispenseResult(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @Path("qr_id") qrId: String,
        @retrofit2.http.Body request: xyz.mdblab.z80.data.api.models.DispenseResultRequest
    ): xyz.mdblab.z80.data.api.models.DispenseResultResponse

    // --- VOID/REFUND ---
    @retrofit2.http.POST("void")
    suspend fun initiateVoid(
        @retrofit2.http.Header("X-API-Key") apiKey: String
    ): xyz.mdblab.z80.data.api.models.VoidInitiateResponse

    @GET("void/{void_id}")
    suspend fun getVoidStatus(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @Path("void_id") voidId: String
    ): xyz.mdblab.z80.data.api.models.VoidStatusResponse

    // --- EMPLOYEE PIN VALIDATION ---
    @retrofit2.http.POST("validate-employee-pin")
    suspend fun validateEmployeePIN(
        @retrofit2.http.Header("X-API-Key") apiKey: String,
        @retrofit2.http.Body request: xyz.mdblab.z80.data.api.models.ValidatePinRequest
    ): xyz.mdblab.z80.data.api.models.ValidatePinResponse

    // --- RECOVERY ---
    @GET("current-payment-request")
    suspend fun getCurrentPaymentRequest(
        @retrofit2.http.Header("X-API-Key") apiKey: String
    ): xyz.mdblab.z80.data.api.models.CurrentPaymentResponse
}
