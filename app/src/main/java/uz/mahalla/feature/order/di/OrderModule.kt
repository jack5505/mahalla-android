package uz.mahalla.feature.order.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import uz.mahalla.feature.order.domain.DefaultOrderVerticalResolver
import uz.mahalla.feature.order.domain.OrderVerticalResolver

/** Резолвер вертикали заказа для deep link'а (issue #343) — через интерфейс: ViewModel тестируется с фейком. */
@Module
@InstallIn(SingletonComponent::class)
interface OrderModule {

    @Binds
    fun bindOrderVerticalResolver(impl: DefaultOrderVerticalResolver): OrderVerticalResolver
}
