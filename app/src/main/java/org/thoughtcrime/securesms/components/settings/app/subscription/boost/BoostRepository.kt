package org.thoughtcrime.securesms.components.settings.app.subscription.boost

import io.reactivex.rxjava3.core.Single
import org.signal.core.util.money.FiatMoney
import org.thoughtcrime.securesms.badges.Badges
import org.thoughtcrime.securesms.badges.models.Badge
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile
import org.whispersystems.signalservice.api.services.DonationsService
import org.whispersystems.signalservice.internal.ServiceResponse
import java.math.BigDecimal
import java.util.Currency

class BoostRepository(private val donationsService: DonationsService) {

  fun getBoosts(currency: Currency): Single<List<Boost>> {
    return donationsService.boostAmounts
      .flatMap(ServiceResponse<Map<String, List<BigDecimal>>>::flattenResult)
      .map { result ->
        val boosts = result[currency.currencyCode] ?: throw Exception("Unsupported currency! ${currency.currencyCode}")
        boosts.map { Boost(FiatMoney(it, currency)) }
      }
  }

  fun getBoostBadge(): Single<Badge> {
    return donationsService.boostBadge
      .flatMap(ServiceResponse<SignalServiceProfile.Badge>::flattenResult)
      .map(Badges::fromServiceBadge)
  }
}
