package com.dark.trainer.data

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

object SupabaseClient {
    val client = createSupabaseClient(
        supabaseUrl = "https://kjschxdipncjysframnk.supabase.co",
        supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imtqc2NoeGRpcG5janlzZnJhbW5rIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzA5Njc3MzgsImV4cCI6MjA4NjU0MzczOH0.NaV1q-NfLpCfcPfKaoFA96Sx9tvXDuzkVsmK-2K5ttA"
    ) {
        install(Postgrest)
        install(Storage)
    }
}