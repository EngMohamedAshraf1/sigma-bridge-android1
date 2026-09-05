package com.sigmabridge.app.di

import com.sigmabridge.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient {
        val url = BuildConfig.SUPABASE_URL.trim()
        val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()

        require(url.isNotBlank()) {
            "SUPABASE_URL is missing. Add it to local.properties."
        }
        require(key.isNotBlank()) {
            "SUPABASE_PUBLISHABLE_KEY is missing. Add it to local.properties."
        }

        return createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Auth) {
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            install(Realtime) {
                reconnectDelay = 5.seconds
            }
            install(Storage)
        }
    }
}
