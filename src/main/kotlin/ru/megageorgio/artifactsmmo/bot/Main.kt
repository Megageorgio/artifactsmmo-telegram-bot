package ru.megageorgio.artifactsmmo.bot

import artifactsmmo.client.CharactersClient
import artifactsmmo.client.GeClient
import artifactsmmo.client.ItemsClient
import artifactsmmo.client.MapsClient
import artifactsmmo.client.MonstersClient
import artifactsmmo.client.MyActionBankDepositClient
import artifactsmmo.client.MyActionBankWithdrawClient
import artifactsmmo.client.MyActionCraftingClient
import artifactsmmo.client.MyActionEquipClient
import artifactsmmo.client.MyActionFightClient
import artifactsmmo.client.MyActionGatheringClient
import artifactsmmo.client.MyActionMoveClient
import artifactsmmo.client.MyActionUnequipClient
import artifactsmmo.client.MyBankItemsClient
import artifactsmmo.client.MyCharactersClient
import artifactsmmo.client.ResourcesClient
import artifactsmmo.models.CharacterSchema
import artifactsmmo.models.CraftingSchema
import artifactsmmo.models.DestinationSchema
import artifactsmmo.models.EquipSchema
import artifactsmmo.models.GEItemSchema
import artifactsmmo.models.ItemSchema
import artifactsmmo.models.MapSchema
import artifactsmmo.models.MonsterSchema
import artifactsmmo.models.ResourceSchema
import artifactsmmo.models.SimpleItemSchema
import artifactsmmo.models.UnequipSchema
import com.github.kotlintelegrambot.Bot
import kotlinx.coroutines.runBlocking

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.entities.ChatId

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.ParseMode.MARKDOWN
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileReader
import java.io.FileWriter

val myBankItemsClient = client<MyBankItemsClient>()
val myCharactersClient = client<MyCharactersClient>()
val charactersClient = client<CharactersClient>()
val mapsClient = client<MapsClient>()
val myActionMoveClient = client<MyActionMoveClient>()
val myActionGatheringClient = client<MyActionGatheringClient>()
val itemsClient = client<ItemsClient>()
val resourcesClient = client<ResourcesClient>()
val monstersClient = client<MonstersClient>()
val myActionFightClient = client<MyActionFightClient>()
val myActionBankDepositClient = client<MyActionBankDepositClient>()
val myActionBankWithdrawClient = client<MyActionBankWithdrawClient>()
val myActionCraftingClient = client<MyActionCraftingClient>()
val myActionEquipClient = client<MyActionEquipClient>()
val myActionUnequipClient = client<MyActionUnequipClient>()
val geClient = client<GeClient>()

@Serializable
enum class State {
  SELECT_CHARACTER,
  SELECT_ACTION,
  SELECT_SHARED_ACTION,
  SELECT_RESOURCE_TYPE,
  SELECT_RESOURCE,
  SELECT_MONSTER,
  SELECT_CRAFTING_TYPE,
  SELECT_CRAFTING,
  SELECT_AUTOCRAFT_MODE,
  SELECT_AMOUNT,
  SELECT_EQUIPMENT_TYPE,
  SELECT_EQUIPMENT,
}

@Serializable
enum class Action(val value: String) {
  REST("resting"),
  GATHER("gathering"),
  FIGHT("fighting"),
  CRAFT("crafting"),
  EQUIP("equipping"),

}

@Serializable
data class CharacterInfo(
  val name: String,
  var x: Int,
  var y: Int,
  var action: Action = Action.REST,
  var data: String = "",
  var extraData: String = "",
  var desc: String = "",
  var amount: Int = 0
)

var currentState = State.SELECT_CHARACTER;
var currentCharacter: CharacterSchema? = null;
val characters = arrayListOf<CharacterInfo>()
val locations = arrayListOf<MapSchema>()
val resources = arrayListOf<ResourceSchema>()
val monsters = arrayListOf<MonsterSchema>()
val items = arrayListOf<ItemSchema>()

suspend fun goToLocation(character: CharacterInfo, code: String): Boolean {
  val location = locations.find { it.content?.code == code }
  if (location == null) {
    return false
  }

  if (character.x != location.x || character.y != location.y) {
    val cooldown = myActionMoveClient.actionMoveMyNameActionMovePost(
      DestinationSchema(location.x, location.y),
      character.name
    ).data.cooldown.totalSeconds
    delay(cooldown * 1000L)
    character.x = location.x
    character.y = location.y
  }

  return true
}

fun getItemFromCode(code: String): ItemSchema? {
  return items.find { it.code == code }
}

fun getItemCraftSkill(code: String): String {
  return getItemFromCode(code)?.craft?.skill ?: ""
}

suspend fun getItemFromBank(character: CharacterInfo, item: SimpleItemSchema) {
  goToLocation(character, "bank")
  delay(
    myActionBankWithdrawClient.actionWithdrawBankMyNameActionBankWithdrawPost(
      item,
      character.name
    ).data.cooldown.totalSeconds * 1000L
  )
}

suspend fun craftItem(character: CharacterInfo) {
  val charData = charactersClient.getCharacterCharactersNameGet(character.name).data

  if (character.action == Action.GATHER) {
    val amount = (charData.inventory?.find { it.code == character.data }?.quantity ?: 0) / 6
    goToLocation(character, getItemCraftSkill(character.extraData))
    delay(
      myActionCraftingClient.actionCraftingMyNameActionCraftingPost(
        CraftingSchema(character.extraData, amount),
        character.name
      ).data.cooldown.totalSeconds * 1000L
    )
  } else if (character.action == Action.CRAFT) {
    if (character.amount == 0) {
      character.action = Action.REST
      character.desc = ""
      return
    }
    clearInventory(character)
    val craftItem = items.find { it.code == character.data }

    if (craftItem?.craft?.items == null) {
      return
    }

    val possibleAmount = charData.inventoryMaxItems / craftItem!!.craft!!.items!!.sumOf { it.quantity }.toInt()
    val resultAmount: Int

    if (possibleAmount >= character.amount) {
      resultAmount = character.amount
      character.amount = 0
    } else {
      resultAmount = possibleAmount
      character.amount -= possibleAmount
    }

    for (materialItem in craftItem!!.craft!!.items!!) {
      getItemFromBank(character, SimpleItemSchema(materialItem.code, resultAmount * materialItem.quantity))
    }

    goToLocation(character, getItemCraftSkill(character.data))
    delay(
      myActionCraftingClient.actionCraftingMyNameActionCraftingPost(
        CraftingSchema(character.data, resultAmount),
        character.name
      ).data.cooldown.totalSeconds * 1000L
    )
  }
}

suspend fun clearInventory(character: CharacterInfo) {
  if (!goToLocation(character, "bank")) {
    character.action = Action.REST
    character.desc = ""
    return
  }
  val inventory = charactersClient.getCharacterCharactersNameGet(character.name).data.inventory
  if (inventory != null) {
    for (item in inventory) {
      if (item.quantity != 0) {
        delay(
          myActionBankDepositClient.actionDepositBankMyNameActionBankDepositPost(
            SimpleItemSchema(
              item.code,
              item.quantity
            ), character.name
          ).data.cooldown.totalSeconds * 1000L
        )
      }
    }
  }
}

suspend fun getBankItems(): List<SimpleItemSchema> {
  var bankItemPages = -1
  var bankItemPage = 1;
  val bankItems = arrayListOf<SimpleItemSchema>()

  do {
    val response = myBankItemsClient.getBankItemsMyBankItemsGet(size = 100, page = bankItemPage)
    if (bankItemPages == -1) {
      bankItemPages = response.pages ?: 1
    }
    bankItems.addAll(response.data)
    bankItemPage += 1
  } while (bankItemPage <= bankItemPages)


  return bankItems
}

suspend fun characterLoop(character: CharacterInfo) {
  while (true) {
    try {

      when (character.action) {
        Action.REST -> {
          delay(100)
        }

        Action.GATHER -> {
          var resourceCode: String? = null
          if (character.data.endsWith("max")) {
            val charData = charactersClient.getCharacterCharactersNameGet(character.name).data
            var currentLevel = 0
            val skill = character.data.split("_")[0]
            when (skill) {
              "woodcutting" -> {
                currentLevel = charData.woodcuttingLevel
              }

              "mining" -> {
                currentLevel = charData.miningLevel
              }

              "fishing" -> {
                currentLevel = charData.fishingLevel
              }
            }
            val resource =
              resources.filter { it.skill == skill && it.level <= currentLevel }.sortedByDescending { it.level }[0]
            resourceCode = resource.code
            character.desc = resource.name
          }
          if (!goToLocation(character, resourceCode ?: character.data)) {
            character.action = Action.REST
            continue
          }

          val cooldown =
            myActionGatheringClient.actionGatheringMyNameActionGatheringPost(character.name).data.cooldown.totalSeconds
          delay(cooldown * 1000L)
        }

        Action.FIGHT -> {
          if (!goToLocation(character, character.data)) {
            character.action = Action.REST
            continue
          }

          val cooldown = myActionFightClient.actionFightMyNameActionFightPost(character.name).data.cooldown.totalSeconds
          delay(cooldown * 1000L)
        }

        Action.CRAFT -> {
          craftItem(character)
          clearInventory(character)
        }

        Action.EQUIP -> {
          items.find { it.code == character.data }
          clearInventory(character)
          try {
            myActionUnequipClient.actionUnequipItemMyNameActionUnequipPost(
              UnequipSchema(character.extraData),
              character.name
            )
            clearInventory(character)
          } catch (_: Exception) {
          }
          getItemFromBank(character, SimpleItemSchema(character.data, 1))
          myActionEquipClient.actionEquipItemMyNameActionEquipPost(
            EquipSchema(character.data, character.extraData),
            character.name
          )
          character.action = Action.REST
          character.desc = ""
        }
      }
    } catch (e: Exception) {
      try {
        if (e.message?.startsWith("[497]") == true) {
          if (character.action == Action.GATHER && character.extraData != "") {
            craftItem(character)
          }

          clearInventory(character)
        } else {
          delay(1000L)
          println(e.message)
        }
      } catch (e: Exception) {
        delay(5000L)
        println(e.message)
        continue
      }
    }
  }
}

fun canDefeatMonster(monster: MonsterSchema): Boolean {
  if (currentCharacter == null) {
    return false
  }
  var monsterDamage = 0
  monsterDamage += monster.attackAir * (1 - currentCharacter!!.resAir / 100)
  monsterDamage += monster.attackFire * (1 - currentCharacter!!.resFire / 100)
  monsterDamage += monster.attackEarth * (1 - currentCharacter!!.resEarth / 100)
  monsterDamage += monster.attackWater * (1 - currentCharacter!!.resWater / 100)

  var characterDamage = 0
  characterDamage += currentCharacter!!.attackAir * (1 + currentCharacter!!.dmgAir / 100) * (1 - monster.resAir / 100)
  characterDamage += currentCharacter!!.attackFire * (1 + currentCharacter!!.dmgFire / 100) * (1 - monster.resFire / 100)
  characterDamage += currentCharacter!!.attackEarth * (1 + currentCharacter!!.dmgEarth / 100) * (1 - monster.resEarth / 100)
  characterDamage += currentCharacter!!.attackWater * (1 + currentCharacter!!.dmgWater / 100) * (1 - monster.resWater / 100)

  val stepsToBeatMonster = monster.hp / characterDamage
  val stepsToBeatPlayer = currentCharacter!!.hp / monsterDamage

  return stepsToBeatPlayer >= stepsToBeatMonster
}

fun calculateCraftAmount(bankItems: List<SimpleItemSchema>, craftItem: ItemSchema): Int {
  if (craftItem.craft?.items == null) {
    return 0
  }

  var minAmount = Int.MAX_VALUE
  for (materialItem in craftItem.craft!!.items!!) {
    val amount = bankItems.find { it.code == materialItem.code }?.quantity?.div(materialItem.quantity) ?: 0
    if (amount < minAmount) {
      minAmount = amount
    }
  }

  return minAmount
}

fun main(): Unit = runBlocking {
  println("made by Megageorgio (https://github.com/Megageorgio)")
  if (File("savedData.json").exists()) {
    FileReader("savedData.json").use { characters.addAll(Json.decodeFromString<List<CharacterInfo>>(it.readText())) }
  }

  val botToken: String
  FileReader("config.json").use {
    botToken =
      Json.parseToJsonElement(it.readText()).jsonObject["botToken"]?.jsonPrimitive?.content ?: ""
  }

  locations.addAll(mapsClient.getAllMapsMapsGet(size = 100, contentType = "resource").data)
  locations.addAll(mapsClient.getAllMapsMapsGet(size = 100, contentType = "monster").data)
  locations.addAll(mapsClient.getAllMapsMapsGet(size = 100, contentType = "bank").data)
  locations.addAll(mapsClient.getAllMapsMapsGet(size = 100, contentType = "workshop").data)
  resources.addAll(resourcesClient.getAllResourcesResourcesGet(size = 100).data)
  monsters.addAll(monstersClient.getAllMonstersMonstersGet(size = 100).data)

  var itemPages = -1;
  var itemPage = 1;
  do {
    val response = itemsClient.getAllItemsItemsGet(size = 100, page = itemPage)
    if (itemPages == -1) {
      itemPages = response.pages ?: 1
    }
    items.addAll(response.data)
    itemPage += 1
  } while (itemPage <= itemPages)

  for (character in myCharactersClient.getMyCharactersMyCharactersGet().data) {
    val oldCharacter = characters.find { it.name == character.name }
    if (oldCharacter == null) {
      characters.add(CharacterInfo(character.name, character.x, character.y))
    } else {
      oldCharacter.x = character.x
      oldCharacter.y = character.y
    }

  }

  var messageId = -1L

  val characterNames = characters.map { it.name }
  for (characterName in characterNames) {
    launch {
      characters.find { c -> c.name == characterName }?.let { characterLoop(it) }
    }
  }

  var currentCharacterInfo = characters[0]

  fun sendOrEditMessage(
    bot: Bot,
    chatId: Long,
    text: String,
    replyMarkup: InlineKeyboardMarkup,
    extraText: String = ""
  ) {
    var resultText: String;
    if (extraText != "") {
      resultText = extraText + "\n"
    } else {
      resultText = characters.joinToString("\n", postfix = "\n\n") {
        var charStr = it.name + " is " + it.action.value
        if (it.desc != "") {
          charStr += " " + it.desc
        }
        if (it.amount != 0) {
          charStr += " x" + it.amount
        }
        charStr += " (" + it.x + ", " + it.y + ")"
        charStr
      }
    }

    resultText += "*$text*"

    if (messageId == -1L) {
      messageId = bot.sendMessage(
        chatId = ChatId.fromId(chatId),
        text = resultText,
        replyMarkup = replyMarkup,
        parseMode = MARKDOWN
      ).get().messageId

      return
    }

    bot.editMessageText(chatId = ChatId.fromId(chatId), messageId = messageId, text = resultText, parseMode = MARKDOWN)
    bot.editMessageReplyMarkup(chatId = ChatId.fromId(chatId), messageId = messageId, replyMarkup = replyMarkup)

    FileWriter("savedData.json").use { it.write(Json.encodeToString(characters)) }
  }

  fun sendSelectCharacterMenu(bot: Bot, chatId: Long) {
    currentState = State.SELECT_CHARACTER

    val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
      characterNames.map {
        listOf(
          InlineKeyboardButton.CallbackData(
            text = it,
            callbackData = it
          )
        )
      }.plusElement(
        listOf(
          InlineKeyboardButton.CallbackData(
            text = "Shared",
            callbackData = "shared"
          )
        )
      )
    )

    sendOrEditMessage(
      bot = bot,
      chatId = chatId,
      text = "Select character",
      replyMarkup = inlineKeyboardMarkup,
    )
  }

  fun sendSelectActionMenu(bot: Bot, chatId: Long, extraText: String = "") {
    currentState = State.SELECT_ACTION

    val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
      listOf(InlineKeyboardButton.CallbackData(text = "Rest", callbackData = "rest")),
      listOf(InlineKeyboardButton.CallbackData(text = "Gathering", callbackData = "gathering")),
      listOf(InlineKeyboardButton.CallbackData(text = "Fighting", callbackData = "fighting")),
      listOf(InlineKeyboardButton.CallbackData(text = "Crafting", callbackData = "crafting")),
      listOf(InlineKeyboardButton.CallbackData(text = "Equip", callbackData = "equip")),
      //listOf(InlineKeyboardButton.CallbackData(text = "Buy", callbackData = "buy")),
      //listOf(InlineKeyboardButton.CallbackData(text = "Sell", callbackData = "sell")),
      listOf(InlineKeyboardButton.CallbackData(text = "Info", callbackData = "info")),
      listOf(InlineKeyboardButton.CallbackData(text = "Back", callbackData = "back")),
    )

    sendOrEditMessage(
      bot = bot,
      chatId = chatId,
      text = "Select action",
      replyMarkup = inlineKeyboardMarkup,
      extraText = extraText
    )
  }

  fun sendSelectSharedActionMenu(bot: Bot, chatId: Long, extraText: String = "") {
    currentState = State.SELECT_SHARED_ACTION

    val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
      listOf(InlineKeyboardButton.CallbackData(text = "Bank Info", callbackData = "bank")),
      listOf(InlineKeyboardButton.CallbackData(text = "Market Info", callbackData = "market")),
      listOf(InlineKeyboardButton.CallbackData(text = "Back", callbackData = "back")),
    )

    sendOrEditMessage(
      bot = bot,
      chatId = chatId,
      text = "Select action",
      replyMarkup = inlineKeyboardMarkup,
      extraText = extraText
    )
  }

  fun sendSelectResourceTypeMenu(bot: Bot, chatId: Long) {
    currentState = State.SELECT_RESOURCE_TYPE

    val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
      listOf(InlineKeyboardButton.CallbackData(text = "Woodcutting", callbackData = "woodcutting")),
      listOf(InlineKeyboardButton.CallbackData(text = "Mining", callbackData = "mining")),
      listOf(InlineKeyboardButton.CallbackData(text = "Fishing", callbackData = "fishing")),
      listOf(InlineKeyboardButton.CallbackData(text = "Back", callbackData = "back")),
    )

    sendOrEditMessage(
      bot = bot,
      chatId = chatId,
      text = "Select resource type",
      replyMarkup = inlineKeyboardMarkup,
    )
  }

  fun sendSelectCraftingTypeMenu(bot: Bot, chatId: Long) {
    currentState = State.SELECT_CRAFTING_TYPE

    val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
      listOf(InlineKeyboardButton.CallbackData(text = "Weaponcrafting", callbackData = "weaponcrafting")),
      listOf(InlineKeyboardButton.CallbackData(text = "Gearcrafting", callbackData = "gearcrafting")),
      listOf(InlineKeyboardButton.CallbackData(text = "Jewelrycrafting", callbackData = "jewelrycrafting")),
      listOf(InlineKeyboardButton.CallbackData(text = "Cooking", callbackData = "cooking")),
      listOf(InlineKeyboardButton.CallbackData(text = "Woodcutting", callbackData = "woodcutting")),
      listOf(InlineKeyboardButton.CallbackData(text = "Mining", callbackData = "mining")),
      listOf(InlineKeyboardButton.CallbackData(text = "Back", callbackData = "back")),
    )

    sendOrEditMessage(
      bot = bot,
      chatId = chatId,
      text = "Select resource type",
      replyMarkup = inlineKeyboardMarkup,
    )
  }

  fun getCurrentCharacterEquipment(code: String?): String {
    if (code == null) {
      return ""
    }

    val item = getItemFromCode(code!!) ?: return ""

    return " (" + item.name + ")"
  }

  fun sendSelectSlotMenu(bot: Bot, chatId: Long) {
    currentState = State.SELECT_EQUIPMENT_TYPE

    val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Weapon" + getCurrentCharacterEquipment(currentCharacter?.weaponSlot),
          callbackData = "weapon"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Shield" + getCurrentCharacterEquipment(currentCharacter?.shieldSlot),
          callbackData = "shield"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Helmet" + getCurrentCharacterEquipment(currentCharacter?.helmetSlot),
          callbackData = "helmet"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Body Armor" + getCurrentCharacterEquipment(currentCharacter?.bodyArmorSlot),
          callbackData = "body_armor"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Leg Armor" + getCurrentCharacterEquipment(currentCharacter?.legArmorSlot),
          callbackData = "leg_armor"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Boots" + getCurrentCharacterEquipment(currentCharacter?.bootsSlot),
          callbackData = "boots"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Ring 1" + getCurrentCharacterEquipment(currentCharacter?.ring1Slot),
          callbackData = "ring1"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Ring 2" + getCurrentCharacterEquipment(currentCharacter?.ring2Slot),
          callbackData = "ring2"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Amulet" + getCurrentCharacterEquipment(currentCharacter?.amuletSlot),
          callbackData = "amulet"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Artifact 1" + getCurrentCharacterEquipment(currentCharacter?.artifact1Slot),
          callbackData = "artifact1"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Artifact 2" + getCurrentCharacterEquipment(currentCharacter?.artifact2Slot),
          callbackData = "artifact2"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Artifact 3" + getCurrentCharacterEquipment(currentCharacter?.artifact3Slot),
          callbackData = "artifact3"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Consumable 1" + getCurrentCharacterEquipment(currentCharacter?.consumable1Slot),
          callbackData = "consumable1"
        )
      ),
      listOf(
        InlineKeyboardButton.CallbackData(
          text = "Consumable 2" + getCurrentCharacterEquipment(currentCharacter?.consumable2Slot),
          callbackData = "consumable2"
        )
      ),
      listOf(InlineKeyboardButton.CallbackData(text = "Back", callbackData = "back")),
    )

    sendOrEditMessage(
      bot = bot,
      chatId = chatId,
      text = "Select slot",
      replyMarkup = inlineKeyboardMarkup,
    )
  }

  val bot = bot {
    token = botToken
    timeout = 60

    dispatch {
      command("start") {
        sendSelectCharacterMenu(bot, message.chat.id)
      }

      callbackQuery {
        val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
        when (currentState) {
          State.SELECT_CHARACTER -> {
            if (callbackQuery.data == "shared") {
              sendSelectSharedActionMenu(bot, chatId)
              return@callbackQuery
            }

            if (!characterNames.contains(callbackQuery.data)) {
              bot.sendMessage(ChatId.fromId(chatId), "No character with this name")
              return@callbackQuery
            }

            currentCharacterInfo = characters.find { c -> c.name == callbackQuery.data }!!
            currentCharacter = charactersClient.getCharacterCharactersNameGet(callbackQuery.data).data

            sendSelectActionMenu(bot, chatId)
          }

          State.SELECT_ACTION -> {
            when (callbackQuery.data) {
              "back" -> {
                sendSelectCharacterMenu(bot, chatId)
              }

              "rest" -> {
                currentCharacterInfo.data = ""
                currentCharacterInfo.extraData = ""
                currentCharacterInfo.desc = ""
                currentCharacterInfo.amount = 0
                currentCharacterInfo.action = Action.REST

                sendSelectActionMenu(bot, chatId)
              }

              "gathering" -> {
                sendSelectResourceTypeMenu(bot, chatId)
              }

              "fighting" -> {
                currentState = State.SELECT_MONSTER

                val inlineKeyboardMarkup = InlineKeyboardMarkup.create(monsters.filter { canDefeatMonster(it) }.map {
                  listOf(
                    InlineKeyboardButton.CallbackData(
                      text = it.name + " (" + it.level + " lvl)",
                      callbackData = it.code
                    )
                  )
                }.plusElement(listOf(InlineKeyboardButton.CallbackData(text = "Back", callbackData = "back"))))

                sendOrEditMessage(
                  bot = bot,
                  chatId = chatId,
                  text = "Select monster",
                  replyMarkup = inlineKeyboardMarkup,
                )
              }

              "crafting" -> {
                sendSelectCraftingTypeMenu(bot, chatId)
              }

              "equip" -> {
                sendSelectSlotMenu(bot, chatId)
              }

              "info" -> {
                currentCharacter = charactersClient.getCharacterCharactersNameGet(currentCharacter!!.name).data

                var extraText = ""
                extraText += "Name: " + currentCharacter!!.name + "\n"
                extraText += "Level: " + currentCharacter!!.level + " (" + currentCharacter!!.xp + "/" + currentCharacter!!.maxXp + ")\n"
                extraText += "Mining: " + currentCharacter!!.miningLevel + " (" + currentCharacter!!.miningXp + "/" + currentCharacter!!.miningMaxXp + ")\n"
                extraText += "Woodcutting: " + currentCharacter!!.woodcuttingLevel + " (" + currentCharacter!!.woodcuttingXp + "/" + currentCharacter!!.woodcuttingMaxXp + ")\n"
                extraText += "Fishing: " + currentCharacter!!.fishingLevel + " (" + currentCharacter!!.fishingXp + "/" + currentCharacter!!.fishingMaxXp + ")\n"
                extraText += "Weaponcrafting: " + currentCharacter!!.weaponcraftingLevel + " (" + currentCharacter!!.weaponcraftingXp + "/" + currentCharacter!!.weaponcraftingMaxXp + ")\n"
                extraText += "Gearcrafting: " + currentCharacter!!.gearcraftingLevel + " (" + currentCharacter!!.gearcraftingXp + "/" + currentCharacter!!.gearcraftingMaxXp + ")\n"
                extraText += "Jewelrycrafting: " + currentCharacter!!.jewelrycraftingLevel + " (" + currentCharacter!!.jewelrycraftingXp + "/" + currentCharacter!!.jewelrycraftingMaxXp + ")\n"
                extraText += "Cooking: " + currentCharacter!!.cookingLevel + " (" + currentCharacter!!.cookingXp + "/" + currentCharacter!!.cookingMaxXp + ")\n"
                extraText += "HP: " + currentCharacter!!.hp + "\n"
                extraText += "Haste: " + currentCharacter!!.haste + "\n"
                extraText += "Gold: " + currentCharacter!!.gold + "\n"
                extraText += "Inventory (" + currentCharacter!!.inventory!!.sumOf { it.quantity } + "/" + currentCharacter!!.inventoryMaxItems + "):\n"
                for (item in currentCharacter!!.inventory!!) {
                  if (item.code != "") {
                    extraText += "- " + getItemFromCode(item.code)!!.name + " x" + item.quantity + "\n"
                  }
                }

                sendSelectActionMenu(bot, chatId, extraText)
              }
            }
          }

          State.SELECT_SHARED_ACTION -> {
            when (callbackQuery.data) {
              "back" -> {
                sendSelectCharacterMenu(bot, chatId)
              }

              "bank" -> {
                val bankItems = getBankItems()

                var extraText = "Bank Items (" + bankItems.sumOf { it.quantity } + "):\n"
                for (item in bankItems) {
                  extraText += "- " + getItemFromCode(item.code)!!.name + " x" + item.quantity + "\n"
                }

                sendSelectSharedActionMenu(bot, chatId, extraText)
              }

              "market" -> {
                var marketItemPages = -1;
                var marketItemPage = 1;
                val marketItems = arrayListOf<GEItemSchema>()
                do {
                  val response = geClient.getAllGeItemsGeGet(size = 100, page = marketItemPage)
                  if (marketItemPages == -1) {
                    marketItemPages = response.pages ?: 1
                  }
                  marketItems.addAll(response.data)
                  marketItemPage += 1
                } while (marketItemPage <= marketItemPages)

                var extraText = "Market Items:\n"
                for (item in marketItems) {
                  if (item.stock != 0) {
                    extraText += "- " + getItemFromCode(item.code)!!.name + " x" + item.stock + " (Buy: " + item.buyPrice + ", Sell: " + item.sellPrice + ")\n"
                  }
                }

                sendSelectSharedActionMenu(bot, chatId, extraText)
              }
            }
          }

          State.SELECT_RESOURCE_TYPE -> {
            currentState = State.SELECT_RESOURCE

            var currentLevel = 0
            when (callbackQuery.data) {
              "back" -> {
                sendSelectActionMenu(bot, chatId)
                return@callbackQuery
              }

              "woodcutting" -> {
                currentLevel = currentCharacter?.woodcuttingLevel ?: 0
              }

              "mining" -> {
                currentLevel = currentCharacter?.miningLevel ?: 0
              }

              "fishing" -> {
                currentLevel = currentCharacter?.fishingLevel ?: 0
              }
            }
            val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
              resources.filter { it.skill == callbackQuery.data && it.level <= currentLevel }
                .map { (listOf(InlineKeyboardButton.CallbackData(text = it.name, callbackData = it.code))) }
                .plusElement(
                  listOf(
                    InlineKeyboardButton.CallbackData(
                      text = "Max",
                      callbackData = callbackQuery.data + "_max"
                    )
                  )
                ).plusElement(listOf(InlineKeyboardButton.CallbackData(text = "Back", callbackData = "back")))
            )

            sendOrEditMessage(
              bot = bot,
              chatId = chatId,
              text = "Select resource",
              replyMarkup = inlineKeyboardMarkup,
            )
          }

          State.SELECT_MONSTER -> {
            if (callbackQuery.data == "back") {
              sendSelectActionMenu(bot, chatId)
              return@callbackQuery
            }

            currentCharacterInfo.data = callbackQuery.data
            currentCharacterInfo.extraData = ""
            currentCharacterInfo.desc = monsters.find { it.code == callbackQuery.data }?.name ?: ""
            currentCharacterInfo.action = Action.FIGHT
            sendSelectActionMenu(bot, chatId)
          }

          State.SELECT_RESOURCE -> {
            if (callbackQuery.data == "back") {
              sendSelectActionMenu(bot, chatId)
              return@callbackQuery
            }

            currentState = State.SELECT_AUTOCRAFT_MODE

            currentCharacterInfo.data = callbackQuery.data
            currentCharacterInfo.extraData = ""
            currentCharacterInfo.desc = resources.find { it.code == callbackQuery.data }?.name ?: ""
            currentCharacterInfo.action = Action.GATHER

            val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
              listOf(InlineKeyboardButton.CallbackData(text = "Yes", callbackData = "craft")),
              listOf(InlineKeyboardButton.CallbackData(text = "No", callbackData = "no_craft")),
            )

            sendOrEditMessage(
              bot = bot,
              chatId = chatId,
              text = "Should autocraft?",
              replyMarkup = inlineKeyboardMarkup,
            )
          }

          State.SELECT_CRAFTING_TYPE -> {
            currentState = State.SELECT_CRAFTING

            var currentLevel = 0

            val bankItems = getBankItems()

            when (callbackQuery.data) {
              "back" -> {
                sendSelectActionMenu(bot, chatId)
                return@callbackQuery
              }

              "weaponcrafting" -> {
                currentLevel = currentCharacter?.weaponcraftingLevel ?: 0
              }

              "gearcrafting" -> {
                currentLevel = currentCharacter?.gearcraftingLevel ?: 0
              }

              "jewelrycrafting" -> {
                currentLevel = currentCharacter?.jewelrycraftingLevel ?: 0
              }

              "cooking" -> {
                currentLevel = currentCharacter?.cookingLevel ?: 0
              }

              "woodcutting" -> {
                currentLevel = currentCharacter?.woodcuttingLevel ?: 0
              }

              "mining" -> {
                currentLevel = currentCharacter?.miningLevel ?: 0
              }

            }

            val inlineKeyboardMarkup = InlineKeyboardMarkup.create(items.filter {
              it.craft?.skill == callbackQuery.data && it.craft!!.level!! <= currentLevel && calculateCraftAmount(
                bankItems,
                it
              ) > 0
            }.map {
              (listOf(
                InlineKeyboardButton.CallbackData(
                  text = it.name + " (" + calculateCraftAmount(
                    bankItems,
                    it
                  ) + ")", callbackData = it.code + "-" + calculateCraftAmount(bankItems, it)
                )
              ))
            }.plusElement(listOf(InlineKeyboardButton.CallbackData(text = "Back", callbackData = "back"))))

            sendOrEditMessage(
              bot = bot,
              chatId = chatId,
              text = "Select item",
              replyMarkup = inlineKeyboardMarkup,
            )
          }

          State.SELECT_CRAFTING -> {
            if (callbackQuery.data == "back") {
              sendSelectActionMenu(bot, chatId)
              return@callbackQuery
            }

            currentState = State.SELECT_AMOUNT

            currentCharacterInfo.action = Action.REST
            currentCharacterInfo.data = callbackQuery.data.split("-")[0]
            currentCharacterInfo.amount = callbackQuery.data.split("-")[1].toInt()
            currentCharacterInfo.extraData = ""
            currentCharacterInfo.desc = getItemFromCode(currentCharacterInfo.data)?.name ?: ""

            val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
              listOf(
                InlineKeyboardButton.CallbackData(
                  text = "Max (" + currentCharacterInfo.amount + ")",
                  callbackData = "max"
                )
              ),
              listOf(InlineKeyboardButton.CallbackData(text = "Back", callbackData = "back")),
            )

            sendOrEditMessage(
              bot = bot,
              chatId = chatId,
              text = "Enter amount",
              replyMarkup = inlineKeyboardMarkup,
            )
          }

          State.SELECT_AUTOCRAFT_MODE -> {
            var shouldCraft = false

            when (callbackQuery.data) {
              "craft" -> {
                shouldCraft = true
              }

              "no_craft" -> {
                shouldCraft = false
              }
            }

            if (shouldCraft) {
              currentCharacterInfo.extraData =
                items.find { it.type == "resource" && it.craft?.items?.find { it.code == currentCharacterInfo.data && it.quantity == 6 } != null }?.code
                  ?: ""
            }

            sendSelectActionMenu(bot, chatId)
          }

          State.SELECT_AMOUNT -> {
            if (callbackQuery.data == "back") {
              sendSelectActionMenu(bot, chatId)
              return@callbackQuery
            }

            currentCharacterInfo.action = Action.CRAFT
            sendSelectActionMenu(bot, chatId)
          }

          State.SELECT_EQUIPMENT_TYPE -> {
            if (callbackQuery.data == "back") {
              sendSelectActionMenu(bot, chatId)
              return@callbackQuery
            }

            currentState = State.SELECT_EQUIPMENT
            currentCharacterInfo.extraData = callbackQuery.data

            val bankItems = getBankItems()

            val inlineKeyboardMarkup = InlineKeyboardMarkup.create(bankItems.map { getItemFromCode(it.code) }.filter {
              it != null && currentCharacter?.level!! >= it.level && it.type == callbackQuery.data.replace(
                "[0-9]".toRegex(),
                ""
              )
            }
              .map { (listOf(InlineKeyboardButton.CallbackData(text = it!!.name, callbackData = it.code))) }
              .plusElement(listOf(InlineKeyboardButton.CallbackData(text = "Back", callbackData = "back"))))

            sendOrEditMessage(
              bot = bot,
              chatId = chatId,
              text = "Select equipment",
              replyMarkup = inlineKeyboardMarkup,
            )
          }

          State.SELECT_EQUIPMENT -> {
            if (callbackQuery.data == "back") {
              sendSelectSlotMenu(bot, chatId)
              return@callbackQuery
            }

            currentCharacterInfo.action = Action.EQUIP
            currentCharacterInfo.data = callbackQuery.data
            currentCharacterInfo.amount = 0
            currentCharacterInfo.desc = getItemFromCode(callbackQuery.data)?.name ?: ""

            sendSelectActionMenu(bot, chatId)
          }
        }
      }

      message {
        if (currentState == State.SELECT_CHARACTER) {
          sendSelectCharacterMenu(bot, message.chat.id)
        } else if (currentState == State.SELECT_AMOUNT) {
          try {
            currentCharacterInfo.amount = message.text?.toInt() ?: 0
            currentCharacterInfo.action = Action.CRAFT
            sendSelectActionMenu(bot, message.chat.id)
          } catch (e: NumberFormatException) {
          }
        }
      }

      telegramError {
        println(error.getErrorMessage())
      }
    }
  }

  println("bot started")

  bot.startPolling()
}