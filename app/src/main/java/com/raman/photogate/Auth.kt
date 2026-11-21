package com.raman.photogate

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks

object Auth {
    // Add-only: the app can upload to the library but cannot read or delete anything in it.
    private val SCOPE = Scope("https://www.googleapis.com/auth/photoslibrary.appendonly")

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    /** Access token if the user already granted access, null if they still need to connect. Call off the main thread. */
    fun silentToken(c: Context): String? {
        val result = Tasks.await(Identity.getAuthorizationClient(c).authorize(request()))
        return if (result.hasResolution()) null else result.accessToken
    }
}
