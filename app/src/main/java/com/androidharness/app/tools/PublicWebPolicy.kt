package com.androidharness.app.tools

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.OkHttpClient

/** Public research cannot reach loopback, LAN services or device-hosted HTTP endpoints. */
internal object PublicWebPolicy {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b=address.address.map { it.toInt() and 255 }
        if(b.size==16) return (b[0] and 0xe0)==0x20 // global-unicast IPv6 only
        if(b.size!=4) return false
        return !(b[0]==0 || b[0]==10 || b[0]==127 || b[0]>=224 ||
            (b[0]==100 && b[1] in 64..127) || (b[0]==169 && b[1]==254) ||
            (b[0]==172 && b[1] in 16..31) || (b[0]==192 && b[1]==168) ||
            (b[0]==192 && b[1]==0 && b[2]==0) || (b[0]==198 && b[1] in 18..19))
    }
    private val publicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses=Dns.SYSTEM.lookup(hostname)
            if(addresses.isEmpty() || addresses.any { !isPublic(it) })
                throw UnknownHostException("Only public websites are allowed; local/device network addresses are blocked.")
            return addresses
        }
    }

    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .dns(publicDns)
        // OkHttp can bypass Dns for numeric IPs. Validate before any connection,
        // and follow redirects here so each destination is checked before use.
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            var request=chain.request()
            for(step in 0..5) {
                publicDns.lookup(request.url.host)
                if(request.url.username.isNotEmpty() || request.url.password.isNotEmpty())
                    throw java.io.IOException("Public webpage URLs cannot contain credentials.")
                val response=chain.proceed(request)
                if(response.code !in setOf(301,302,303,307,308)) return@addInterceptor response
                val target=response.header("Location")?.let { request.url.resolve(it) }
                    ?: return@addInterceptor response
                response.close()
                if(step==5) throw java.io.IOException("Too many public webpage redirects.")
                val next=request.newBuilder().url(target)
                if(target.host!=request.url.host || target.port!=request.url.port || target.scheme!=request.url.scheme) {
                    next.removeHeader("Authorization").removeHeader("Proxy-Authorization").removeHeader("Cookie")
                }
                if(response.code in setOf(301,302,303) && request.method !in setOf("GET","HEAD"))
                    next.method("GET",null).removeHeader("Content-Type").removeHeader("Content-Length").removeHeader("Transfer-Encoding")
                if(response.code in setOf(307,308) && request.method !in setOf("GET","HEAD"))
                    throw java.io.IOException("Non-readonly public webpage redirect was blocked.")
                request=next.build()
            }
            throw java.io.IOException("Public webpage redirect failed.")
        }
        .addNetworkInterceptor { chain ->
            val address=chain.connection()?.route()?.socketAddress?.address
            if(address==null || !isPublic(address)) throw java.io.IOException("Local/device network access is blocked.")
            chain.proceed(chain.request())
        }.build()
}
