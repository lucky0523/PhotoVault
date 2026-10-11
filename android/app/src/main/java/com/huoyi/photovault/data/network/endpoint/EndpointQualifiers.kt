package com.huoyi.photovault.data.network.endpoint

import javax.inject.Qualifier

/** CoroutineScope (SupervisorJob + Dispatchers.IO) owned by [EndpointResolver] for probes and config collection. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EndpointScope
