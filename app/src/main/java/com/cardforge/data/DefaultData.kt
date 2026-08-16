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
            // --- 召喚制限・枚数参照・公開をまとめて使うサーチ魔法 ---
            CardDef(
                id = newId(),
                name = "アララギの号令",
                kind = CardKind.SPELL,
                categoryIds = listOf(araragi),
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    // 効果ではなく発動そのものに付く制限。無効にされても掛かったまま。
                    summonLocks = listOf(
                        SummonLock(
                            who = PlayerRef.SELF,
                            summon = SummonKind.SPECIAL,
                            filters = listOf(CategoryFilter(araragi)),
                            except = true
                        )
                    ),
                    clauses = listOf(
                        EffectClause(
                            mode = ActivationMode.OPTIONAL,
                            // 墓地送りだけ「〜することができる」にする。
                            // 「手札を見せ、〜墓地へ送ることができる」を1つの手順にする。
                            optionalSteps = listOf(1),
                            linkedSteps = listOf(2),
                            actions = listOf(
                                ToHandAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.DECK,
                                        filters = listOf(
                                            CategoryFilter(araragi),
                                            KindFilter(CardKind.SPELL)
                                        ),
                                        count = 1
                                    )
                                ),
                                RevealAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.HAND,
                                        selection = SelectionMode.ALL
                                    ),
                                    RevealDuration.MOMENT
                                ),
                                ToGraveAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.DECK,
                                        filters = listOf(
                                            CategoryFilter(araragi),
                                            KindFilter(CardKind.MONSTER)
                                        ),
                                        countSpec = CountValue(
                                            scope = CardScope(
                                                who = PlayerRef.SELF,
                                                zone = ZoneType.HAND,
                                                filters = listOf(
                                                    CategoryFilter(araragi),
                                                    KindFilter(CardKind.SPELL)
                                                ),
                                                selection = SelectionMode.ALL
                                            ),
                                            multiplier = 1
                                        ),
                                        upTo = true
                                    )
                                )
                            )
                        )
                    )
                ),
                flavor = "旗のもとに集え。"
            ),

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
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.SUMMONED,
                                    selfOnly = true
                                )
                            ),
                            mode = ActivationMode.OPTIONAL,
                            limits = listOf(UsageLimit(LimitScope.SAME_NAME, 1)),
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
                            limits = listOf(UsageLimit(LimitScope.THIS_CARD, 1)),
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
                    costs = listOf(PayLifeCost(500)),
                    limits = listOf(UsageLimit(LimitScope.CATEGORY, 1, araragi)),
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
                                    controller = PlayerRef.SELF,
                                    // 出すときに表示形式を選べる。
                                    positionChoices = listOf(
                                        Position.ATTACK,
                                        Position.DEFENSE
                                    )
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
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    limits = listOf(
                        UsageLimit(
                            scope = LimitScope.SAME_NAME,
                            times = 1,
                            applies = LimitApplies.EACH
                        )
                    ),
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

            // --- 永続効果を持つモンスター ---
            CardDef(
                id = newId(),
                name = "不滅の守護者",
                kind = CardKind.MONSTER,
                level = 6,
                attributeId = light,
                raceId = rock,
                atk = 2000,
                def = 2400,
                flavor = "発動を必要とせず、表側で場にある限りずっと効いている。",
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            mode = ActivationMode.CONTINUOUS,
                            actions = listOf(
                                GrantProtectionAction(
                                    scope = CardScope(selfOnly = true),
                                    kind = ProtectionKind.OPPONENT_EFFECTS
                                )
                            )
                        ),
                        EffectClause(
                            mode = ActivationMode.CONTINUOUS,
                            actions = listOf(
                                ModifyStatAction(
                                    scope = CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.MONSTER_ZONE,
                                        filters = listOf(RaceFilter(races[9].id)),
                                        selection = SelectionMode.ALL
                                    ),
                                    stat = StatKind.DEF,
                                    delta = 400
                                )
                            )
                        )
                    )
                )
            ),

            // --- 永続の効果でカテゴリ全体を強化する魔法 ---
            CardDef(
                id = newId(),
                name = "アララギの旗印",
                kind = CardKind.SPELL,
                categoryIds = listOf(araragi),
                effect = EffectText(
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    clauses = listOf(
                        EffectClause(
                            mode = ActivationMode.ON_ACTIVATION,
                            actions = listOf(DrawAction(PlayerRef.SELF, 1))
                        ),
                        EffectClause(
                            mode = ActivationMode.CONTINUOUS,
                            actions = listOf(
                                ModifyStatAction(
                                    scope = CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.MONSTER_ZONE,
                                        filters = listOf(
                                            CategoryFilter(araragi),
                                            KindFilter(CardKind.MONSTER)
                                        ),
                                        selection = SelectionMode.ALL
                                    ),
                                    stat = StatKind.ATK,
                                    delta = 500
                                )
                            )
                        )
                    )
                )
            ),

            // --- 送られた場所で結果が変わるモンスター（場合分け） ---
            CardDef(
                id = newId(),
                name = "残響の巨神",
                kind = CardKind.MONSTER,
                level = 10,
                attributeId = light,
                raceId = races[18].id,
                atk = 3000,
                def = 2800,
                effect = EffectText(
                    locations = listOf(
                        ActivationLocation.GRAVEYARD,
                        ActivationLocation.BANISHED
                    ),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.LEFT_FIELD,
                                    selfOnly = true,
                                    cause = CauseFilter.BY_OPPONENT_EFFECT
                                )
                            ),
                            mode = ActivationMode.OPTIONAL,
                            branchMode = BranchMode.FIRST_MATCH,
                            branches = listOf(
                                EffectBranch(
                                    conditions = listOf(
                                        SelfZoneCondition(listOf(ZoneType.GRAVEYARD))
                                    ),
                                    actions = listOf(
                                        ToHandAction(
                                            CardScope(
                                                who = PlayerRef.SELF,
                                                zone = ZoneType.DECK,
                                                filters = listOf(
                                                    KindFilter(CardKind.MONSTER),
                                                    LevelFilter(Cmp.GE, 10)
                                                ),
                                                count = 1,
                                                selection = SelectionMode.CHOOSE
                                            )
                                        )
                                    )
                                ),
                                EffectBranch(
                                    conditions = listOf(
                                        SelfZoneCondition(listOf(ZoneType.BANISHED))
                                    ),
                                    actions = listOf(
                                        ToHandAction(CardScope(selfOnly = true))
                                    )
                                )
                            )
                        )
                    )
                )
            ),

            // --- 墓地をデッキに戻してコストにする魔法 ---
            CardDef(
                id = newId(),
                name = "巡りの祈り",
                kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND, ActivationLocation.FIELD),
                    costs = listOf(
                        MoveCost(
                            scope = CardScope(
                                who = PlayerRef.SELF,
                                zone = ZoneType.GRAVEYARD,
                                count = 2,
                                selection = SelectionMode.CHOOSE
                            ),
                            destination = MoveDestination.DECK_BOTTOM
                        )
                    ),
                    clauses = listOf(
                        EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1)))
                    )
                )
            ),

            // --- 墓地の数で威力が変わる魔法 ---
            CardDef(
                id = newId(),
                name = "累なる怨嗟",
                kind = CardKind.SPELL,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                DamageAction(
                                    who = PlayerRef.OPPONENT,
                                    amountValue = CountValue(
                                        scope = CardScope(
                                            who = PlayerRef.SELF,
                                            zone = ZoneType.GRAVEYARD,
                                            filters = listOf(KindFilter(CardKind.MONSTER)),
                                            selection = SelectionMode.ALL
                                        ),
                                        multiplier = 200
                                    )
                                )
                            )
                        )
                    )
                )
            ),

            // --- エンドフェイズにだけ発動できる罠 ---
            CardDef(
                id = newId(),
                name = "宵の帰還",
                kind = CardKind.TRAP,
                effect = EffectText(
                    conditions = listOf(PhaseCondition(listOf(Phase.END))),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                ToHandAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.GRAVEYARD,
                                        filters = listOf(KindFilter(CardKind.MONSTER)),
                                        count = 1,
                                        selection = SelectionMode.CHOOSE
                                    )
                                )
                            )
                        )
                    )
                )
            ),

            // --- デッキから罠をセットする魔法 ---
            CardDef(
                id = newId(),
                name = "先読みの書",
                kind = CardKind.SPELL,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                SetSpellTrapAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.DECK,
                                        filters = listOf(KindFilter(CardKind.TRAP)),
                                        count = 1,
                                        selection = SelectionMode.CHOOSE
                                    )
                                )
                            )
                        )
                    )
                )
            ),

            // --- 手札で発動するモンスター（コストとして墓地へ送る） ---
            CardDef(
                id = newId(),
                name = "警告の妖精",
                kind = CardKind.MONSTER,
                level = 2,
                attributeId = light,
                raceId = races[18].id,
                atk = 800,
                def = 600,
                flavor = "コストとして墓地へ送るので、効果が無効になっても手札には戻らない。",
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    costs = listOf(DiscardSelfCost()),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.SPECIAL_SUMMONED,
                                    who = PlayerRef.OPPONENT,
                                    filters = listOf(KindFilter(CardKind.MONSTER))
                                )
                            ),
                            mode = ActivationMode.OPTIONAL,
                            limits = listOf(UsageLimit(LimitScope.SAME_NAME, 1)),
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

            // --- 手札で発動するモンスター（効果の解決後に墓地へ送る） ---
            CardDef(
                id = newId(),
                name = "嘆きの巫子",
                kind = CardKind.MONSTER,
                level = 2,
                attributeId = dark,
                raceId = caster,
                atk = 600,
                def = 800,
                flavor = "解決してから墓地へ送るので、発動時にはまだ手札にある。",
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    afterActivation = AfterActivation.TO_GRAVE,
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.DAMAGE_TAKEN,
                                    who = PlayerRef.SELF
                                )
                            ),
                            mode = ActivationMode.OPTIONAL,
                            limits = listOf(UsageLimit(LimitScope.SAME_NAME, 1)),
                            actions = listOf(RecoverAction(PlayerRef.SELF, 1000))
                        )
                    )
                )
            ),

            // --- 発動後もフィールドに残る永続魔法 ---
            CardDef(
                id = newId(),
                name = "終わらない祝祭",
                kind = CardKind.SPELL,
                effect = EffectText(
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    limits = listOf(UsageLimit(LimitScope.THIS_CARD, 1)),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(RecoverAction(PlayerRef.SELF, 500))
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
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.DESTROYED,
                                    selfOnly = true
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
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
