package com.cardforge.data

import com.cardforge.model.*

/**
 * 初回起動時に入れておくサンプルデータ。
 * ここにある属性・種族・カテゴリ・カードは全てアプリ内から編集・削除できる。
 */
object DefaultData {

    private fun entries(vararg names: String) = names.map { NamedEntry(newId(), it) }

    fun seed(): Library {
        val attributes = entries("闇", "光", "炎", "水", "地", "風", "神")
        val races = entries(
            "ドラゴン族", "魔法使い族", "戦士族", "獣族", "機械族", "悪魔族",
            "アンデット族", "水族", "炎族", "岩石族", "鳥獣族", "植物族",
            "昆虫族", "雷族", "恐竜族", "魚族", "海竜族", "爬虫類族", "天使族"
        )
        val categories = entries("アララギ", "機械騎士")

        val dark = attributes[0].id
        val light = attributes[1].id
        val earth = attributes[4].id
        val dragon = races[0].id
        val caster = races[1].id
        val warrior = races[2].id
        val machine = races[4].id
        val rock = races[9].id
        val araragi = categories[0].id
        val knight = categories[1].id

        val cards = listOf(
            // --- 効果を持たない通常モンスター ---
            CardDef(
                id = newId(),
                name = "岩石の門番",
                kind = CardKind.MONSTER,
                level = 4,
                attributeId = earth,
                raceId = rock,
                atk = 1800,
                def = 1500,
                flavor = "古い遺跡の入口を守り続けている石像。効果を持たない。"
            ),

            // --- カテゴリを参照するサーチャー ---
            CardDef(
                id = newId(),
                name = "アララギの巫女",
                kind = CardKind.MONSTER,
                level = 3,
                attributeId = dark,
                raceId = caster,
                atk = 1200,
                def = 1000,
                categoryIds = listOf(araragi),
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            timing = EffectTiming.ON_SUMMON,
                            oncePerTurn = true,
                            actions = listOf(
                                ToHandAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.DECK,
                                        filters = listOf(
                                            CategoryFilter(araragi),
                                            KindFilter(CardKind.MONSTER)
                                        ),
                                        count = 1,
                                        selection = SelectionMode.CHOOSE
                                    )
                                )
                            )
                        )
                    )
                )
            ),

            // --- コストと条件を持つ上級モンスター ---
            CardDef(
                id = newId(),
                name = "アララギの守護竜",
                kind = CardKind.MONSTER,
                level = 7,
                attributeId = dark,
                raceId = dragon,
                atk = 2500,
                def = 2000,
                categoryIds = listOf(araragi),
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            timing = EffectTiming.IGNITION,
                            oncePerTurn = true,
                            costs = listOf(DiscardCost(1)),
                            conditions = listOf(
                                CardExistsCondition(
                                    scope = CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.FIELD,
                                        filters = listOf(
                                            CategoryFilter(araragi),
                                            KindFilter(CardKind.MONSTER)
                                        )
                                    ),
                                    atLeast = 1
                                )
                            ),
                            actions = listOf(
                                DestroyAction(
                                    CardScope(
                                        who = PlayerRef.OPPONENT,
                                        zone = ZoneType.MONSTER_ZONE,
                                        count = 1,
                                        selection = SelectionMode.CHOOSE
                                    )
                                )
                            )
                        )
                    )
                )
            ),

            // --- 手札から発動できる魔法（場所の指定つき） ---
            CardDef(
                id = newId(),
                name = "アララギの儀式",
                kind = CardKind.SPELL,
                categoryIds = listOf(araragi),
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND, ActivationLocation.FIELD),
                    costs = listOf(PayLifeCost(500)),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                SpecialSummonAction(
                                    scope = CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.DECK,
                                        filters = listOf(
                                            CategoryFilter(araragi),
                                            KindFilter(CardKind.MONSTER)
                                        ),
                                        count = 1,
                                        selection = SelectionMode.CHOOSE
                                    ),
                                    position = Position.ATTACK,
                                    controller = PlayerRef.SELF
                                )
                            )
                        )
                    )
                )
            ),

            // --- 複数効果を持つ魔法 ---
            CardDef(
                id = newId(),
                name = "知識の巻物",
                kind = CardKind.SPELL,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(DrawAction(PlayerRef.SELF, 2))
                        ),
                        EffectClause(
                            costs = listOf(DiscardCost(1)),
                            actions = listOf(
                                ModifyStatAction(
                                    scope = CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.MONSTER_ZONE,
                                        count = 1,
                                        selection = SelectionMode.CHOOSE
                                    ),
                                    stat = StatKind.ATK,
                                    delta = 800
                                )
                            )
                        )
                    )
                )
            ),

            // --- 無効化する罠 ---
            CardDef(
                id = newId(),
                name = "静寂の結界",
                kind = CardKind.TRAP,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(actions = listOf(NegateAction))
                    )
                )
            ),

            // --- 全体除去の罠 ---
            CardDef(
                id = newId(),
                name = "崩落の罠",
                kind = CardKind.TRAP,
                effect = EffectText(
                    conditions = listOf(
                        CardExistsCondition(
                            scope = CardScope(
                                who = PlayerRef.OPPONENT,
                                zone = ZoneType.MONSTER_ZONE,
                                filters = listOf(KindFilter(CardKind.MONSTER))
                            ),
                            atLeast = 2
                        )
                    ),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                DestroyAction(
                                    CardScope(
                                        who = PlayerRef.OPPONENT,
                                        zone = ZoneType.MONSTER_ZONE,
                                        selection = SelectionMode.ALL
                                    )
                                )
                            )
                        )
                    )
                )
            ),

            // --- もうひとつのカテゴリのモンスター ---
            CardDef(
                id = newId(),
                name = "機械騎士アルマ",
                kind = CardKind.MONSTER,
                level = 5,
                attributeId = light,
                raceId = machine,
                atk = 2100,
                def = 1700,
                categoryIds = listOf(knight),
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            timing = EffectTiming.ON_DESTROYED,
                            actions = listOf(
                                DamageAction(PlayerRef.OPPONENT, 800)
                            )
                        )
                    )
                )
            ),

            CardDef(
                id = newId(),
                name = "機械騎士ヴェル",
                kind = CardKind.MONSTER,
                level = 4,
                attributeId = light,
                raceId = warrior,
                atk = 1700,
                def = 1200,
                categoryIds = listOf(knight)
            )
        )

        val starter = Deck(
            id = newId(),
            name = "スターターデッキ",
            // 各カードを3枚ずつ入れて 27 枚のデッキにする。
            cardIds = cards.flatMap { card -> List(3) { card.id } }
        )

        return Library(
            master = MasterData(
                attributes = attributes,
                races = races,
                categories = categories
            ),
            cards = cards,
            decks = listOf(starter)
        )
    }
}
