package xyz.mdblab.z80.data.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // Base URL from raspberries-config.yml
    private const val BASE_URL = "https://apimt.uruvending.com/mt/api/v1/vending/" 

    val instance: VendingApi by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(VendingApi::class.java)
    }
}
