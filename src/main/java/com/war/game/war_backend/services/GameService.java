package com.war.game.war_backend.services;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.war.game.war_backend.controller.dto.request.AttackRequestDto;
import com.war.game.war_backend.events.AIActionExecutedEvent;
import com.war.game.war_backend.events.AIActionIntentEvent;
import com.war.game.war_backend.events.GameOverEvent;
import com.war.game.war_backend.exceptions.InvalidGamePhaseException;
import com.war.game.war_backend.model.AITurnAction;
import com.war.game.war_backend.model.Card;
import com.war.game.war_backend.model.Game;
import com.war.game.war_backend.model.GameTerritory;
import com.war.game.war_backend.model.Objective;
import com.war.game.war_backend.model.Player;
import com.war.game.war_backend.model.PlayerCard;
import com.war.game.war_backend.model.PlayerGame;
import com.war.game.war_backend.model.Territory;
import com.war.game.war_backend.model.enums.CardType;
import com.war.game.war_backend.model.enums.GameConstants;
import com.war.game.war_backend.model.enums.GameStatus;
import com.war.game.war_backend.model.enums.PlayerType;
import com.war.game.war_backend.repository.CardRepository;
import com.war.game.war_backend.repository.GameRepository;
import com.war.game.war_backend.repository.GameTerritoryRepository;
import com.war.game.war_backend.repository.ObjectiveRepository;
import com.war.game.war_backend.repository.PlayerCardRepository;
import com.war.game.war_backend.repository.PlayerGameRepository;
import com.war.game.war_backend.repository.PlayerRepository;
import com.war.game.war_backend.repository.TerritoryBorderRepository;
import com.war.game.war_backend.repository.TerritoryRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GameService {

  @PersistenceContext private EntityManager entityManager;

  private final GameRepository gameRepository;
  private final PlayerGameRepository playerGameRepository;
  private final TerritoryRepository territoryRepository;
  private final ObjectiveRepository objectiveRepository;
  private final GameTerritoryRepository gameTerritoryRepository;
  private final PlayerCardRepository playerCardRepository;
  private final CardRepository cardRepository;
  private final TerritoryBorderRepository territoryBorderRepository;
  private final WinConditionService winConditionService;
  private final PlayerRepository playerRepository;

  private final ApplicationEventPublisher eventPublisher;

  private final SimpMessagingTemplate messagingTemplate;
  private final PlayerService playerService;
  private final Random random = new Random();

  // Bônus por continente (inglês)
  private static final Map<String, Integer> CONTINENT_BONUSES =
      Map.of(
          "Asia", 7,
          "North America", 5,
          "Europe", 5,
          "Africa", 3,
          "South America", 2,
          "Oceania", 2);

  // Mapeamento de nomes de continentes do banco (português) para inglês
  private static final Map<String, String> CONTINENT_NAME_MAP =
      Map.ofEntries(
          Map.entry("América do Norte", "North America"),
          Map.entry("América do Sul", "South America"),
          Map.entry("Europa", "Europe"),
          Map.entry("África", "Africa"),
          Map.entry("Ásia", "Asia"),
          Map.entry("Oceania", "Oceania"));

  // Método auxiliar para remover jogador de lobbies ativos
  @Transactional
  public void removePlayerFromActiveLobbies(Player player) {
    List<PlayerGame> activeLobbies =
        playerGameRepository.findByPlayerAndGame_Status(player, GameStatus.LOBBY.name());

    for (PlayerGame activeLobbyPlayerGame : activeLobbies) {
      Game activeLobby = activeLobbyPlayerGame.getGame();
      boolean wasOwner = activeLobbyPlayerGame.getIsOwner();
      Long lobbyId = activeLobby.getId();
      Long playerGameIdToDelete = activeLobbyPlayerGame.getId();

      // Remove da coleção do Game para evitar que o cascade re-persista
      activeLobby.getPlayerGames().remove(activeLobbyPlayerGame);

      // Executa o delete nativo (SQL direto) que ignora o cache do Hibernate
      playerGameRepository.deleteByIdNative(playerGameIdToDelete);

      // Força flush e limpa o cache do EntityManager
      playerGameRepository.flush();
      entityManager.clear();

      // Busca os jogadores restantes diretamente do banco (após o delete)
      List<PlayerGame> remainingPlayers = playerGameRepository.findByGame(activeLobby);

      // Se o jogador era dono, transfere a propriedade ou deleta o lobby
      if (wasOwner) {
        if (!remainingPlayers.isEmpty()) {
          // Define o próximo jogador como novo dono
          PlayerGame newOwner = remainingPlayers.get(0);
          newOwner.setIsOwner(true);
          playerGameRepository.save(newOwner);
          playerGameRepository.flush();

          // Envia notificação WebSocket usando os jogadores atualizados do banco
          notifyLobbyUpdateWithPlayers(lobbyId, remainingPlayers);
        } else {
          // Se não houver mais jogadores, exclui o lobby
          gameRepository.deleteById(lobbyId);
          gameRepository.flush();
        }
      } else {
        // Jogador comum saiu, notifica o lobby usando os jogadores atualizados
        notifyLobbyUpdateWithPlayers(lobbyId, remainingPlayers);
      }
    }
  }

  // Método auxiliar para notificar mudanças em um lobby via WebSocket (usando lista de jogadores
  // atualizada)
  private void notifyLobbyUpdateWithPlayers(Long lobbyId, List<PlayerGame> currentPlayers) {
    List<com.war.game.war_backend.controller.dto.response.PlayerLobbyDtoResponse> playerDtos =
        currentPlayers.stream()
            .map(
                pg ->
                    new com.war.game.war_backend.controller.dto.response.PlayerLobbyDtoResponse(
                        pg.getId(),
                        pg.getPlayer().getUsername(),
                        pg.getColor(),
                        pg.getIsOwner(),
                        pg.getPlayer().getImageUrl()))
            .collect(Collectors.toList());

    messagingTemplate.convertAndSend("/topic/lobby/" + lobbyId + "/state", playerDtos);
  }

  // LOBBY =======================================

  @Transactional
  public Game createNewLobby(String lobbyName, Player creator) {
    // Remove o jogador de outros lobbies ativos antes de criar um novo
    removePlayerFromActiveLobbies(creator);

    // Cria o novo lobby
    Game newGame = new Game();
    newGame.setName(lobbyName);
    newGame.setStatus(GameStatus.LOBBY.name());
    newGame.setCreatedAt(LocalDateTime.now());

    gameRepository.save(newGame);

    // O criador do lobby é o primeiro jogador, atribuímos a primeira cor da lista
    String assignedColor = GameConstants.AVAILABLE_COLORS.get(0);

    // Cria a entidade PlayerGame para o criador
    PlayerGame creatorPlayerGame = new PlayerGame();
    creatorPlayerGame.setGame(newGame);
    creatorPlayerGame.setPlayer(creator);
    creatorPlayerGame.setIsOwner(true);
    creatorPlayerGame.setStillInGame(true);
    creatorPlayerGame.setColor(assignedColor);
    creatorPlayerGame.setUsername(creator.getUsername());
    creatorPlayerGame.setImageUrl(creator.getImageUrl());

    playerGameRepository.save(creatorPlayerGame);

    newGame.getPlayerGames().add(creatorPlayerGame);

    return newGame;
  }

  public List<Game> findAllLobbies() {
    return gameRepository.findByStatusWithPlayers(GameStatus.LOBBY.name());
  }

  /** Retorna partidas finalizadas (status = FINISHED). */
  @Transactional(readOnly = true)
  public List<Game> findFinishedGames() {
    return gameRepository.findByStatus(GameStatus.FINISHED.name());
  }

  public Game findCurrentGameForPlayer(Player player) {
    // Busca qualquer jogo ativo do jogador (lobby ou em andamento)
    List<PlayerGame> activeGames = playerGameRepository.findByPlayerAndStillInGame(player, true);

    if (activeGames.isEmpty()) {
      return null;
    }

    // Retorna o jogo mais recente (último criado)
    return activeGames.stream()
        .map(PlayerGame::getGame)
        .filter(
            game ->
                !GameStatus.FINISHED.name().equals(game.getStatus())
                    && !GameStatus.CANCELED.name().equals(game.getStatus()))
        .max((g1, g2) -> g1.getCreatedAt().compareTo(g2.getCreatedAt()))
        .orElse(null);
  }

  @Transactional
  public Game addPlayerToLobby(Long lobbyId, Player player) {
    Game game =
        gameRepository
            .findByIdWithPlayers(lobbyId)
            .orElseThrow(() -> new RuntimeException("Lobby não encontrado."));

    if (!GameStatus.LOBBY.name().equals(game.getStatus())) {
      throw new RuntimeException(
          "Não é possível entrar. O jogo já foi iniciado ou tem status inválido.");
    }

    Optional<PlayerGame> existingPlayerGame =
        playerGameRepository.findByGameAndPlayer(game, player);

    if (existingPlayerGame.isPresent()) {
      return game;
    }

    removePlayerFromActiveLobbies(player);

    Game currentGame = findCurrentGameForPlayer(player);

    if (currentGame != null && !GameStatus.LOBBY.name().equals(currentGame.getStatus())) {
      throw new RuntimeException(
          "Você já está em um jogo ativo. Saia do jogo atual antes de entrar em outro lobby.");
    }

    Set<PlayerGame> currentPlayers = game.getPlayerGames();

    if (currentPlayers.size() >= GameConstants.MAX_PLAYERS) {
      throw new RuntimeException(
          "Lobby cheio. Número máximo de jogadores alcançado (" + GameConstants.MAX_PLAYERS + ").");
    }

    Set<String> usedColors =
        currentPlayers.stream()
            .map(PlayerGame::getColor)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());

    String assignedColor =
        GameConstants.AVAILABLE_COLORS.stream()
            .filter(color -> !usedColors.contains(color))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Erro interno: Nenhuma cor disponível."));

    PlayerGame newPlayerGame = new PlayerGame();
    newPlayerGame.setGame(game);
    newPlayerGame.setPlayer(player);
    newPlayerGame.setIsOwner(false);
    newPlayerGame.setStillInGame(true);
    newPlayerGame.setColor(assignedColor);
    newPlayerGame.setUsername(player.getUsername());
    newPlayerGame.setImageUrl(player.getImageUrl());

    playerGameRepository.save(newPlayerGame);

    game.getPlayerGames().add(newPlayerGame);

    return game;
  }

  @Transactional
  public Game removePlayerFromLobby(Long lobbyId, Player player) {
    Game game =
        gameRepository
            .findById(lobbyId)
            .orElseThrow(() -> new RuntimeException("Lobby não encontrado."));

    if (!GameStatus.LOBBY.name().equals(game.getStatus())) {
      throw new RuntimeException("Não é possível sair. O jogo já foi iniciado.");
    }

    // Encontra a entidade PlayerGame para remover
    PlayerGame playerGame =
        playerGameRepository
            .findByGameAndPlayer(game, player)
            .orElseThrow(() -> new RuntimeException("Jogador não está no lobby."));

    // Reove o player
    game.getPlayerGames().remove(playerGame);

    // Remove a entidade de relacionamento do banco de dados
    playerGameRepository.delete(playerGame);

    // Lógica para o dono: se o dono sair, o próximo vira o dono
    if (playerGame.getIsOwner()) {
      Set<PlayerGame> remainingPlayersSet = game.getPlayerGames();

      if (!remainingPlayersSet.isEmpty()) {
        // Converte para lista para pegar o 'primeiro'
        List<PlayerGame> remainingPlayersList = new ArrayList<>(remainingPlayersSet);

        // Define o próximo jogador como novo dono
        PlayerGame newOwner = remainingPlayersList.get(0);
        newOwner.setIsOwner(true);
        playerGameRepository.save(newOwner);

      } else {
        // Se não houver mais jogadores, o lobby é excluído
        gameRepository.delete(game);
        return null; // Retorna null para sinalizar que o lobby foi excluído
      }
    }

    return game;
  }

  @Transactional
  public Game removePlayerFromGame(Long gameId, Player player) {
    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new RuntimeException("Jogo não encontrado."));

    // Encontra o PlayerGame do jogador
    PlayerGame playerGame =
        playerGameRepository
            .findByGameAndPlayer(game, player)
            .orElseThrow(() -> new RuntimeException("Jogador não está neste jogo."));

    // Marca o jogador como fora do jogo (stillInGame = false)
    playerGame.setStillInGame(false);
    playerGameRepository.save(playerGame);

    // Se era o turno desse jogador, passa para o próximo
    if (game.getTurnPlayer() != null && game.getTurnPlayer().getId().equals(playerGame.getId())) {

      // Busca próximo jogador ativo
      List<PlayerGame> activePlayers =
          playerGameRepository.findByGame(game).stream()
              .filter(PlayerGame::getStillInGame)
              .sorted(Comparator.comparing(PlayerGame::getTurnOrder))
              .collect(Collectors.toList());

      if (!activePlayers.isEmpty()) {
        // Encontra o próximo jogador na ordem
        int currentIndex = -1;
        for (int i = 0; i < activePlayers.size(); i++) {
          if (activePlayers.get(i).getTurnOrder() > playerGame.getTurnOrder()) {
            currentIndex = i;
            break;
          }
        }

        // Se não encontrou ninguém depois, volta para o primeiro
        if (currentIndex == -1) {
          currentIndex = 0;
        }

        PlayerGame nextPlayer = activePlayers.get(currentIndex);
        game.setTurnPlayer(nextPlayer);
        gameRepository.save(game);
      } else {
        // Não há mais jogadores ativos, finaliza o jogo
        game.setStatus(GameStatus.FINISHED.name());
        gameRepository.save(game);
      }
    }

    // Transfere territórios do jogador que saiu para jogadores ativos
    List<GameTerritory> playerTerritories =
        gameTerritoryRepository.findByGameAndOwner(game, playerGame);

    if (!playerTerritories.isEmpty()) {
      List<PlayerGame> activePlayers =
          playerGameRepository.findByGame(game).stream()
              .filter(PlayerGame::getStillInGame)
              .collect(Collectors.toList());

      if (!activePlayers.isEmpty()) {
        // Distribui territórios entre jogadores ativos de forma round-robin
        int playerIndex = 0;
        for (GameTerritory territory : playerTerritories) {
          territory.setOwner(activePlayers.get(playerIndex));
          gameTerritoryRepository.save(territory);

          playerIndex = (playerIndex + 1) % activePlayers.size();
        }
      }
    }

    // Verifica se só restou 1 jogador ativo (vencedor)
    long activePlayersCount =
        playerGameRepository.findByGame(game).stream().filter(PlayerGame::getStillInGame).count();

    if (activePlayersCount == 1) {
      // Encontra o vencedor
      PlayerGame winner =
          playerGameRepository.findByGame(game).stream()
              .filter(PlayerGame::getStillInGame)
              .findFirst()
              .orElseThrow(() -> new RuntimeException("Erro ao determinar vencedor."));

      // Dispara evento de vitória por eliminação
      winConditionService.checkWinConditions(game, winner);
    }

    return game;
  }

  public static class AttackResult {
    public final List<Integer> attackerDice;
    public final List<Integer> defenderDice;
    public final Game game;

    public AttackResult(List<Integer> attackerDice, List<Integer> defenderDice, Game game) {
      this.attackerDice = attackerDice;
      this.defenderDice = defenderDice;
      this.game = game;
    }
  }

  // Ouve as intenções de ação da IA (GameService é o Executor).
  @EventListener
  public void handleAIActionIntent(AIActionIntentEvent event) {
    Long gameId = event.getGameId();
    String aiUsername = event.getAiUsername();
    AITurnAction action = event.getAction();

    boolean turnIsFinished = false;

    System.out.println("GameService - Recebida INTENÇÃO de ação da IA: " + action.getType());

    try {
      // Executa a lógica de jogo com base na intenção da IA
      switch (action.getType()) {
        case CARD_TRADE:
          this.tradeCardsForReinforcements(gameId, aiUsername, action.getCardIds());
          break;
        case REINFORCE_ALLOCATION:
          this.allocateTroops(
              gameId,
              aiUsername,
              Long.valueOf(action.getTargetTerritoryId()),
              action.getNumberOfArmies());
          break;
        case ATTACK:
          this.attackTerritory(
              gameId,
              aiUsername,
              new AttackRequestDto(
                  Long.valueOf(action.getSourceTerritoryId()),
                  Long.valueOf(action.getTargetTerritoryId()),
                  action.getNumberOfArmies()));
          break;
        case FORTIFY:
          this.moveTroops(
              gameId,
              aiUsername,
              Long.valueOf(action.getSourceTerritoryId()),
              Long.valueOf(action.getTargetTerritoryId()),
              action.getNumberOfArmies());
          break;
        case PASS_PHASE:
          this.startNextTurn(gameId, aiUsername);
          break;
        case PASS_TURN:
          this.startNextTurn(gameId, aiUsername);
          turnIsFinished = true;
          break;
        default:
          System.err.println(
              "GameService: Tipo de ação da IA não reconhecido: " + action.getType());
      }

    } catch (Exception e) {
      System.err.println(
          "GameService - Erro ao executar ação da IA: "
              + action.getType()
              + " - "
              + e.getMessage());
      // Lógica de tratamento de erro: talvez publicar um evento de erro
    } finally {
      // Publica o feedback de execução de volta para a IA
      eventPublisher.publishEvent(
          new AIActionExecutedEvent(this, gameId, aiUsername, action.getType(), turnIsFinished));
    }
  }

  // EM JOGO =====================================

  @Transactional
  public Game startGame(Long gameId, String initiatingUsername) {
    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new RuntimeException("Lobby não encontrado."));

    if (!GameStatus.LOBBY.name().equals(game.getStatus())) {
      throw new RuntimeException("O jogo já foi iniciado ou tem status inválido.");
    }

    Player initiatingPlayer = playerService.getPlayerByUsername(initiatingUsername);

    List<PlayerGame> playerGames = playerGameRepository.findByGame(game);

    // Validação de Dono e Mínimo de Jogadores
    playerGames.stream()
        .filter(PlayerGame::getIsOwner)
        .filter(pg -> pg.getPlayer().equals(initiatingPlayer))
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Apenas o dono do lobby pode iniciar a partida."));

    if (playerGames.size() < 2) {
      throw new RuntimeException("São necessários pelo menos 2 jogadores para iniciar o jogo.");
    }

    // --- INÍCIO DA LÓGICA DE DISTRIBUIÇÃO ---

    // Definição da Ordem de Turnos
    Collections.shuffle(playerGames, new Random());
    for (int i = 0; i < playerGames.size(); i++) {
      playerGames.get(i).setTurnOrder(i + 1);
    }

    // Cálculo e Atribuição de Tropas
    int initialTroops = calculateInitialTroops(playerGames.size());

    for (PlayerGame pg : playerGames) {
      pg.setUnallocatedArmies(initialTroops);
    }

    distributeObjectivesSmartly(playerGames);

    // Distribuição de Territórios
    List<Territory> allTerritories = territoryRepository.findAll();
    Collections.shuffle(allTerritories, new Random());

    // Assume que distributeTerritories lida com a criação e atribuição inicial de 1 exército em
    // cada território.
    List<GameTerritory> initialGameTerritories =
        distributeTerritories(game, playerGames, allTerritories);

    // Salva as mudanças
    playerGameRepository.saveAll(playerGames);
    gameTerritoryRepository.saveAll(initialGameTerritories);

    PlayerGame firstPlayer =
        playerGames.stream()
            .filter(pg -> pg.getTurnOrder() == 1)
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Erro ao definir o primeiro jogador."));

    game.setStatus(GameStatus.SETUP_ALLOCATION.name());

    game.setTurnPlayer(firstPlayer);

    if (firstPlayer.getPlayer().getType() != PlayerType.HUMAN) {
      System.out.println("Iniciando alocação inicial começando pela IA");
      Game savedGame = gameRepository.save(game);
      // Dispara o turno da IA assíncronamente
      Game finalState =
          this.executeAIAction(savedGame.getId(), firstPlayer.getPlayer().getUsername());
      return finalState;
    }
    System.out.println("Não foi pela IA");
    return gameRepository.save(game);
  }

  @Transactional // A mesma para alocação inicial e de reforço
  public Game allocateTroops(Long gameId, String username, Long territoryId, Integer count) {
    System.out.println("\n=== INÍCIO ALOCAÇÃO DE TROPAS ===");
    System.out.println("GameId: " + gameId);
    System.out.println("Username: " + username);
    System.out.println("TerritoryId (recebido): " + territoryId);
    System.out.println("Count: " + count);

    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new RuntimeException("Partida não encontrada."));

    String currentStatus = game.getStatus();
    System.out.println("Game Status: " + currentStatus);

    if (!GameStatus.SETUP_ALLOCATION.name().equals(currentStatus)
        && !GameStatus.REINFORCEMENT.name().equals(currentStatus)) {
      throw new InvalidGamePhaseException(
          "Não é a fase de alocação de tropas. Fase atual: " + currentStatus,
          currentStatus,
          "SETUP_ALLOCATION ou REINFORCEMENT");
    }

    Player player = playerService.getPlayerByUsername(username);
    PlayerGame currentPlayerGame =
        playerGameRepository
            .findByGameAndPlayer(game, player)
            .orElseThrow(() -> new RuntimeException("Jogador não está na partida."));

    System.out.println("CurrentPlayerGame ID: " + currentPlayerGame.getId());

    // Validação de tropas e count
    if (currentPlayerGame.getUnallocatedArmies() < count || count <= 0) {
      throw new RuntimeException("Quantidade de tropas inválida ou superior à sua reserva.");
    }

    // Validação de turno
    if ((GameStatus.REINFORCEMENT.name().equals(currentStatus)
            || GameStatus.SETUP_ALLOCATION.name().equals(currentStatus))
        && game.getTurnPlayer() != null
        && !game.getTurnPlayer().getId().equals(currentPlayerGame.getId())) {
      throw new RuntimeException("Não é a sua vez de alocar tropas.");
    }

    // ENCONTRAR E VALIDAR O TERRITÓRIO
    GameTerritory gameTerritory =
        gameTerritoryRepository
            .findByGameAndTerritoryId(game, territoryId)
            .orElseThrow(() -> new RuntimeException("Território não encontrado nesta partida."));

    System.out.println("\n--- VALIDAÇÃO DE POSSE (ALOCAÇÃO) ---");
    System.out.println("GameTerritory encontrado:");
    System.out.println("  - GameTerritory ID: " + gameTerritory.getId());
    System.out.println("  - Territory ID: " + gameTerritory.getTerritory().getId());
    System.out.println("  - Territory Name: " + gameTerritory.getTerritory().getName());
    System.out.println("  - Owner (PlayerGame) ID: " + gameTerritory.getOwner().getId());
    System.out.println("  - Owner Username: " + gameTerritory.getOwner().getPlayer().getUsername());
    System.out.println("CurrentPlayerGame ID: " + currentPlayerGame.getId());
    System.out.println(
        "IDs iguais? " + gameTerritory.getOwner().getId().equals(currentPlayerGame.getId()));

    // Validação de Posse - Compara IDs ao invés de objetos
    if (!gameTerritory.getOwner().getId().equals(currentPlayerGame.getId())) {
      System.out.println(
          "ERRO: Owner ID ("
              + gameTerritory.getOwner().getId()
              + ") != CurrentPlayer ID ("
              + currentPlayerGame.getId()
              + ")");
      throw new RuntimeException("Você só pode colocar tropas em seus próprios territórios.");
    }

    System.out.println("Validação de posse OK - Alocando " + count + " tropas");

    // APLICAR A ALOCAÇÃO
    // Tropas alocadas são sempre estáticas e podem se mover
    gameTerritory.setStaticArmies(gameTerritory.getStaticArmies() + count);
    currentPlayerGame.setUnallocatedArmies(currentPlayerGame.getUnallocatedArmies() - count);

    // LÓGICA DE TRANSIÇÃO DE FASE

    // Verifica se a reserva de tropas do jogador zerou
    if (currentPlayerGame.getUnallocatedArmies() == 0) {

      if (GameStatus.SETUP_ALLOCATION.name().equals(currentStatus)) {

        List<PlayerGame> remainingAllocators =
            playerGameRepository.findByGame(game).stream()
                .filter(PlayerGame::getStillInGame) // Checa se ainda está no jogo
                .filter(pg -> pg.getUnallocatedArmies() > 0)
                .sorted(Comparator.comparing(PlayerGame::getTurnOrder))
                .collect(Collectors.toList());

        if (remainingAllocators.isEmpty()) {
          // TODOS terminaram a alocação inicial. Transição para o 1º turno de Jogo.

          // Mudar para a fase de REFORÇO do primeiro jogador
          game.setStatus(GameStatus.REINFORCEMENT.name());

          // O primeiro jogador já foi setado corretamente no startGame, só precisamos
          // confirmar.
          PlayerGame firstTurnPlayer =
              playerGameRepository.findByGame(game).stream()
                  .filter(pg -> pg.getTurnOrder() == 1)
                  .findFirst()
                  .orElseThrow(
                      () -> new RuntimeException("Erro ao definir o jogador inicial do jogo."));

          // O primeiro jogador deve CALCULAR e ATRIBUIR as tropas de REFORÇO
          int reinforcementTroops = calculateReinforcementTroops(game, firstTurnPlayer);
          firstTurnPlayer.setUnallocatedArmies(reinforcementTroops);
          playerGameRepository.save(firstTurnPlayer); // Salva o reforço calculado

          game.setTurnPlayer(firstTurnPlayer); // Garante que o turno é dele

          // Checa se o primeiro jogador é IA
          if (firstTurnPlayer.getPlayer().getType() != PlayerType.HUMAN) {

            Game savedGame = gameRepository.save(game);

            System.out.println(
                "IA - Chamando para o primeiro turno da partida de ("
                    + firstTurnPlayer.getPlayer().getUsername()
                    + ")");

            Game finalState =
                this.executeAIAction(savedGame.getId(), firstTurnPlayer.getPlayer().getUsername());

            return finalState; // Retorna o jogo APÓS a IA finalizar o turno
          }
        } else {
          // Passa para o próximo jogador que ainda precisa alocar
          PlayerGame nextPlayerGame = remainingAllocators.get(0);
          game.setTurnPlayer(nextPlayerGame);

          if (nextPlayerGame.getPlayer().getType() != PlayerType.HUMAN) {
            Game savedGame = gameRepository.save(game);
            playerGameRepository.save(currentPlayerGame);
            System.out.println(
                "GameService - Disparando turno da IA no meio do setup inicial: "
                    + nextPlayerGame.getPlayer().getUsername());

            // Chama a ação da IA
            Game finalState =
                this.executeAIAction(savedGame.getId(), nextPlayerGame.getPlayer().getUsername());
            return finalState;
          }
        }

      } else if (GameStatus.REINFORCEMENT.name().equals(currentStatus)) {
        // O jogador da vez terminou a alocação de reforço. Transição para a FASE DE ATAQUE.
        game.setStatus(GameStatus.ATTACK.name());
      }
    }

    gameTerritoryRepository.save(gameTerritory);
    playerGameRepository.save(currentPlayerGame);
    return gameRepository.save(game);
  }

  @Transactional(readOnly = true)
  public int calculateReinforcementTroops(Game game, PlayerGame playerGame) {

    // Contar Territórios do Jogador
    List<GameTerritory> controlledTerritories =
        gameTerritoryRepository.findByGameAndOwner(game, playerGame);
    int totalTerritories = controlledTerritories.size();

    // Tropas nº de territórios / 2 com mínimo de 3
    int territoryTroops = Math.max(3, totalTerritories / 2);
    int continentTroops = 0;

    // Agrupa os territórios por continente para verificar a posse total
    Map<String, Long> territoriesPerContinent =
        controlledTerritories.stream()
            .collect(
                Collectors.groupingBy(
                    gt -> gt.getTerritory().getContinent(), Collectors.counting()));

    // Obtém todos os nomes de continentes únicos do mapa
    List<String> allContinents =
        territoryRepository.findAll().stream()
            .map(Territory::getContinent)
            .distinct()
            .collect(Collectors.toList());

    for (String continentName : allContinents) {
      // Conta quantos territórios o jogador tem neste continente
      Long playerTerritoryCount = territoriesPerContinent.getOrDefault(continentName, 0L);

      // Conta quantos territórios existem neste continente
      long totalContinentTerritories = territoryRepository.countByContinent(continentName);

      // Checa se o jogador tem todos os territórios do continente
      if (playerTerritoryCount == totalContinentTerritories) {
        // Mapeia o nome do continente para inglês se necessário
        String bonusKey = CONTINENT_NAME_MAP.getOrDefault(continentName, continentName);
        continentTroops += CONTINENT_BONUSES.getOrDefault(bonusKey, 0);
      }
    }

    return territoryTroops + continentTroops;
  }

  @Transactional
  public Game startNextTurn(Long gameId, String initiatingUsername) {
    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new RuntimeException("Partida não encontrada."));

    String currentStatus = game.getStatus();

    if (GameStatus.SETUP_ALLOCATION.name().equals(currentStatus)) {
      // Bloqueia tentativas de encerrar o turno durante a alocação inicial.
      throw new RuntimeException("Não é possível encerrar o turno na fase de Alocação Inicial.");
    }

    // Checamos se o status é um dos que permite o avanço de turno
    if (GameStatus.LOBBY.name().equals(currentStatus)
        || GameStatus.FINISHED.name().equals(currentStatus)
        || GameStatus.CANCELED.name().equals(currentStatus)) {

      throw new InvalidGamePhaseException(
          "A ação de encerrar o turno não é válida na fase atual: " + currentStatus,
          currentStatus,
          "REINFORCEMENT, ATTACK ou MOVEMENT");
    }

    // Achar o jogador atual e validar se a chamada é dele
    PlayerGame currentPlayerGame = game.getTurnPlayer();
    if (!currentPlayerGame.getPlayer().getUsername().equals(initiatingUsername)) {
      throw new RuntimeException("Você não tem permissão para encerrar o turno de outro jogador.");
    }

    boolean isCurrentPlayerAI =
        currentPlayerGame.getPlayer().getType() != PlayerType.HUMAN; // Vê se é IA

    // --- LÓGICA DE TRANSIÇÃO DE FASES ---

    if (GameStatus.REINFORCEMENT.name().equals(currentStatus)) {
      // Se estiver em Reforço, o 'endTurn' avança para o Ataque.

      // Regra: O jogador deve alocar todas as tropas antes de avançar para Ataque.
      if (currentPlayerGame.getUnallocatedArmies() > 0) {
        throw new RuntimeException(
            "Você deve alocar todas as suas tropas de reforço ("
                + currentPlayerGame.getUnallocatedArmies()
                + ") antes de avançar para a fase de Ataque.");
      }

      game.setStatus(GameStatus.ATTACK.name());

      // Transição de fase da IA
      if (isCurrentPlayerAI) {
        System.out.println("IA - Chamando ação da IA");
        Game savedGame = gameRepository.save(game);
        return this.executeAIAction(savedGame.getId(), initiatingUsername);
      }

    } else if (GameStatus.ATTACK.name().equals(currentStatus)) {
      // Se estiver em Ataque, o 'endTurn' avança para Movimentação.
      game.setStatus(GameStatus.MOVEMENT.name());

      // Transição de fase da IA
      if (isCurrentPlayerAI) {
        System.out.println("IA - Chamando ação da IA");
        Game savedGame = gameRepository.save(game);
        return this.executeAIAction(savedGame.getId(), initiatingUsername);
      }

    } else if (GameStatus.MOVEMENT.name().equals(currentStatus)) {

      // --- LÓGICA DE FIM DE TURNO E PASSAGEM DE VEZ ---

      // 1. Recompensa de Carta (se conquistou)
      long currentCards = playerCardRepository.countByPlayerGame(currentPlayerGame);

      if (currentPlayerGame.getConqueredTerritoryThisTurn() && currentCards < 5) {
        drawCard(currentPlayerGame);
      }

      // 2. Reset de Flag
      currentPlayerGame.setConqueredTerritoryThisTurn(false);

      // 3. Converter tropas movidas em estáticas (início de novo turno)
      List<GameTerritory> allTerritories = gameTerritoryRepository.findByGame(game);
      for (GameTerritory territory : allTerritories) {
        if (territory.getMovedInArmies() > 0) {
          territory.setStaticArmies(territory.getStaticArmies() + territory.getMovedInArmies());
          territory.setMovedInArmies(0);
        }
      }
      gameTerritoryRepository.saveAll(allTerritories);

      // 4. Buscar os jogadores ativos, ordenados por turnOrder
      List<PlayerGame> activePlayers =
          playerGameRepository.findByGame(game).stream()
              .filter(PlayerGame::getStillInGame) // <--- FILTRO CRUCIAL
              .sorted(Comparator.comparing(PlayerGame::getTurnOrder))
              .collect(Collectors.toList());

      if (activePlayers.size() <= 1) {
        PlayerGame winner = activePlayers.stream().findFirst().orElse(null);
        if (winner != null) {
          throw new RuntimeException(
              "Tentativa de avanço de turno com jogo já finalizado ou com um único jogador ativo.");
        }
        throw new RuntimeException("Erro de estado do jogo. Nenhum jogador ativo para avançar.");
      }

      // 5. Determinar o Próximo Jogador Ativo

      // Encontrar o índice do jogador atual na lista ATIVA
      int currentPlayerIndex = activePlayers.indexOf(currentPlayerGame);

      // O próximo índice na ordem circular dos ativos
      int nextPlayerIndex = (currentPlayerIndex + 1) % activePlayers.size();
      PlayerGame nextPlayerGame = activePlayers.get(nextPlayerIndex);

      // 5. Transição do Turno
      game.setTurnPlayer(nextPlayerGame);

      // 6. Cálculo e Atribuição de Tropas de Reforço
      int reinforcementTroops = calculateReinforcementTroops(game, nextPlayerGame);
      nextPlayerGame.setUnallocatedArmies(reinforcementTroops);

      // 7. Mudar o Status para a fase de Alocação (Início do novo turno)
      game.setStatus(GameStatus.REINFORCEMENT.name());

      // 8. Checar se o próximo jogador é uma IA
      if (nextPlayerGame.getPlayer().getType() != PlayerType.HUMAN) {
        Game savedGame = gameRepository.save(game);

        // Dispara o turno da IA assíncronamente
        System.out.println("IA - Chamando ação da IA");
        Game finalState =
            this.executeAIAction(savedGame.getId(), nextPlayerGame.getPlayer().getUsername());
        return finalState;
      }

      playerGameRepository.save(currentPlayerGame);
      playerGameRepository.save(nextPlayerGame);

    } else {
      throw new RuntimeException(
          "O jogo não está em uma fase de turno conhecida ou a ação não é válida.");
    }

    return gameRepository.save(game);
  }

  @Transactional
  public Game tradeCardsForReinforcements(
      Long gameId, String username, List<Long> playerCardIdsToTrade) {
    // Validações
    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new RuntimeException("Partida não encontrada."));
    Player player = playerService.getPlayerByUsername(username);
    PlayerGame playerGame =
        playerGameRepository
            .findByGameAndPlayer(game, player)
            .orElseThrow(() -> new RuntimeException("Jogador não está na partida."));

    // Compara IDs ao invés de objetos
    if (!game.getTurnPlayer().getId().equals(playerGame.getId())) {
      throw new RuntimeException("Não é o seu turno.");
    }
    if (!GameStatus.REINFORCEMENT.name().equals(game.getStatus())) {
      throw new InvalidGamePhaseException(
          "Só é permitido trocar cartas na fase de reforço. Fase atual: " + game.getStatus(),
          game.getStatus(),
          "REINFORCEMENT");
    }

    // Busca as entidades PlayerCard e Card
    List<PlayerCard> playerCardsToTrade =
        playerCardRepository.findByPlayerGameAndIdIn(playerGame, playerCardIdsToTrade);

    if (playerCardsToTrade.size() != 3) {
      throw new RuntimeException("Você deve selecionar exatamente 3 cartas para a troca.");
    }

    List<Card> cardsToTrade =
        playerCardsToTrade.stream().map(PlayerCard::getCard).collect(Collectors.toList());

    // Valida o Conjunto de Troca
    if (!isTradeSetValid(cardsToTrade)) {
      throw new RuntimeException("O conjunto de cartas não é válido para troca.");
    }

    // Calcula e Atribui Tropas
    int bonusTroops = calculateCardBonus(game);

    // Calcula e aplica o bônus de território diretamente
    calculateTerritoryMatchBonus(game, playerGame, cardsToTrade);

    // Adiciona as tropas à reserva do jogador
    playerGame.setUnallocatedArmies(playerGame.getUnallocatedArmies() + bonusTroops);

    // Remove as Cartas
    playerCardRepository.deleteAll(playerCardsToTrade);

    // Atualiza o Contador Global de Trocas
    game.setCardSetExchangeCount(game.getCardSetExchangeCount() + 1);

    return gameRepository.save(game);
  }

  @Transactional
  public AttackResult attackTerritory(
      Long gameId, String initiatingUsername, AttackRequestDto dto) {
    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new RuntimeException("Partida não encontrada."));

    if (!GameStatus.ATTACK.name().equals(game.getStatus())) {
      throw new InvalidGamePhaseException(
          "Ação inválida. A partida não está na fase de Ataque. Fase atual: " + game.getStatus(),
          game.getStatus(),
          "ATTACK");
    }

    PlayerGame currentPlayerGame = game.getTurnPlayer();

    if (!currentPlayerGame.getPlayer().getUsername().equals(initiatingUsername)) {
      throw new RuntimeException("Não é o seu turno para atacar.");
    }

    GameTerritory sourceTerritory =
        gameTerritoryRepository
            .findByGame_IdAndTerritory_Id(gameId, dto.getSourceTerritoryId())
            .orElseThrow(() -> new RuntimeException("Território atacante não encontrado."));
    GameTerritory targetTerritory =
        gameTerritoryRepository
            .findByGame_IdAndTerritory_Id(gameId, dto.getTargetTerritoryId())
            .orElseThrow(() -> new RuntimeException("Território defensor não encontrado."));

    if (!sourceTerritory.getOwner().getId().equals(currentPlayerGame.getId())) {
      throw new RuntimeException("O território atacante não pertence a você.");
    }

    if (targetTerritory.getOwner().getId().equals(currentPlayerGame.getId())) {
      throw new RuntimeException("Você não pode atacar seu próprio território.");
    }

    boolean isNeighbor =
        territoryBorderRepository
            .findByTerritoryIds(
                sourceTerritory.getTerritory().getId(), targetTerritory.getTerritory().getId())
            .isPresent();

    if (!isNeighbor) {
      throw new RuntimeException(
          "O território "
              + targetTerritory.getTerritory().getName()
              + " não é vizinho do território atacante.");
    }

    int armiesAvailable = sourceTerritory.getStaticArmies();
    int movedInArmies = sourceTerritory.getMovedInArmies();

    // Para atacar, precisa de pelo menos 1 tropa estática disponível
    // As movedInArmies não podem atacar, mas podem "segurar" o território
    if (armiesAvailable < 1) {
      throw new RuntimeException(
          "Você precisa de pelo menos 1 exército estático para realizar um ataque.");
    }

    // Se tem apenas 1 tropa estática, só pode atacar se tiver movedInArmies para segurar o
    // território
    if (armiesAvailable == 1 && movedInArmies == 0) {
      throw new RuntimeException(
          "Você precisa de pelo menos 2 exércitos no território atacante para realizar um ataque. (Sem tropas movidas para segurar o território)");
    }

    if (dto.getAttackDiceCount() < 1 || dto.getAttackDiceCount() > 3) {
      throw new RuntimeException("O número de dados de ataque deve estar entre 1 e 3.");
    }

    // Máximo de dados = tropas estáticas disponíveis (considerando que movedInArmies seguram o
    // território)
    int maxAttackDice;
    if (movedInArmies > 0) {
      // Tem tropas movidas para segurar: pode usar TODAS as estáticas
      maxAttackDice = armiesAvailable;
    } else {
      // Não tem tropas movidas: precisa deixar pelo menos 1 estática
      maxAttackDice = armiesAvailable - 1;
    }

    if (dto.getAttackDiceCount() > maxAttackDice) {
      throw new RuntimeException(
          "Você deve deixar pelo menos um exército no território atacante. Máximo de dados de ataque permitido: "
              + maxAttackDice);
    }

    PlayerGame defenderPlayerGame = targetTerritory.getOwner();
    int defenseArmies = targetTerritory.getStaticArmies();
    int defenseDiceCount = Math.min(3, defenseArmies);

    List<Integer> attackRolls = simulateDiceRolls(dto.getAttackDiceCount());
    List<Integer> defenseRolls = simulateDiceRolls(defenseDiceCount);

    int[] combatResult = resolveCombat(attackRolls, defenseRolls);
    int attackerLosses = combatResult[0];
    int defenderLosses = combatResult[1];

    sourceTerritory.setStaticArmies(sourceTerritory.getStaticArmies() - attackerLosses);

    int currentStaticArmies = targetTerritory.getStaticArmies();

    if (currentStaticArmies > defenderLosses) {
      targetTerritory.setStaticArmies(currentStaticArmies - defenderLosses);
    } else {
      targetTerritory.setStaticArmies(0);
      targetTerritory.setMovedInArmies(0);
    }

    if ((targetTerritory.getStaticArmies()) <= 0) {
      int sourceStaticAfterLosses = sourceTerritory.getStaticArmies();
      int attackedArmies = dto.getAttackDiceCount();
      int attackerLossesInRound = attackerLosses;

      int survivingAttackers = attackedArmies - attackerLossesInRound;
      int troopsToMove = Math.max(1, survivingAttackers);
      int maxMoveable = Math.max(0, sourceStaticAfterLosses - 1);

      if (maxMoveable < 1) {
        throw new RuntimeException(
            "Erro: Não é possível mover tropas para ocupar sem deixar o território atacante vazio.");
      }

      if (troopsToMove > maxMoveable) {
        troopsToMove = maxMoveable;
      }

      targetTerritory.setOwner(currentPlayerGame);
      targetTerritory.setStaticArmies(0);
      targetTerritory.setMovedInArmies(troopsToMove);
      sourceTerritory.setStaticArmies(sourceStaticAfterLosses - troopsToMove);

      currentPlayerGame.setConqueredTerritoryThisTurn(true);

      checkGameOver(game, defenderPlayerGame);

      if (!GameStatus.FINISHED.name().equals(game.getStatus())) {
        winConditionService.checkObjectiveCompletion(game, currentPlayerGame);
      }
    }

    gameTerritoryRepository.save(targetTerritory);
    gameTerritoryRepository.save(sourceTerritory);
    playerGameRepository.save(currentPlayerGame);

    return new AttackResult(attackRolls, defenseRolls, game);
  }

  @Transactional
  public Game moveTroops(
      Long gameId,
      String initiatingUsername,
      Long sourceTerritoryId,
      Long targetTerritoryId,
      Integer troopCount) {
    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new RuntimeException("Partida não encontrada."));

    // Valida se está na fase de MOVEMENT
    if (!GameStatus.MOVEMENT.name().equals(game.getStatus())) {
      throw new InvalidGamePhaseException(
          "Ação inválida. A partida não está na fase de Movimentação. Fase atual: "
              + game.getStatus(),
          game.getStatus(),
          "MOVEMENT");
    }

    PlayerGame currentPlayerGame = game.getTurnPlayer();

    if (!currentPlayerGame.getPlayer().getUsername().equals(initiatingUsername)) {
      throw new RuntimeException("Não é o seu turno para mover tropas.");
    }

    // Busca os territórios
    GameTerritory sourceTerritory =
        gameTerritoryRepository
            .findByGame_IdAndTerritory_Id(gameId, sourceTerritoryId)
            .orElseThrow(() -> new RuntimeException("Território de origem não encontrado."));
    GameTerritory targetTerritory =
        gameTerritoryRepository
            .findByGame_IdAndTerritory_Id(gameId, targetTerritoryId)
            .orElseThrow(() -> new RuntimeException("Território de destino não encontrado."));

    // Valida se ambos os territórios pertencem ao jogador atual
    if (!sourceTerritory.getOwner().getId().equals(currentPlayerGame.getId())) {
      throw new RuntimeException("O território de origem não pertence a você.");
    }

    if (!targetTerritory.getOwner().getId().equals(currentPlayerGame.getId())) {
      throw new RuntimeException("O território de destino não pertence a você.");
    }

    // Valida se os territórios são vizinhos
    boolean isNeighbor =
        territoryBorderRepository
            .findByTerritoryIds(
                sourceTerritory.getTerritory().getId(), targetTerritory.getTerritory().getId())
            .isPresent();

    if (!isNeighbor) {
      throw new RuntimeException("O território de destino não é vizinho do território de origem.");
    }

    // Valida número de tropas disponíveis
    int availableArmies = sourceTerritory.getStaticArmies();
    int movedInArmies = sourceTerritory.getMovedInArmies();

    if (troopCount < 1) {
      throw new RuntimeException("É necessário mover pelo menos 1 tropa.");
    }

    // Se tem movedInArmies, pode mover TODAS as staticArmies (movedInArmies seguram o
    // território)
    // Se não tem movedInArmies, deve deixar pelo menos 1 staticArmy
    int maxMoveable;
    if (movedInArmies > 0) {
      maxMoveable = availableArmies; // Pode mover todas
    } else {
      maxMoveable = availableArmies - 1; // Deve deixar pelo menos 1
    }

    if (troopCount > maxMoveable) {
      throw new RuntimeException(
          "Você deve deixar pelo menos 1 tropa no território de origem. Tropas disponíveis para mover: "
              + maxMoveable);
    }

    // Realiza a movimentação
    sourceTerritory.setStaticArmies(sourceTerritory.getStaticArmies() - troopCount);

    // Tropas movidas vão para movedInArmies no destino (só podem defender até o fim do turno)
    targetTerritory.setMovedInArmies(targetTerritory.getMovedInArmies() + troopCount);

    gameTerritoryRepository.save(sourceTerritory);
    gameTerritoryRepository.save(targetTerritory);

    return game;
  }

  @Transactional
  public void checkGameOver(Game game, PlayerGame defeatedPlayer) {
    PlayerGame attackerPlayer = game.getTurnPlayer();

    long territoriesOwned = gameTerritoryRepository.countByOwner(defeatedPlayer);

    if (territoriesOwned == 0) {
      List<PlayerCard> defeatedCards = playerCardRepository.findByPlayerGame(defeatedPlayer);

      for (PlayerCard card : defeatedCards) {
        card.setPlayerGame(attackerPlayer);
      }

      if (!defeatedCards.isEmpty()) {
        playerCardRepository.saveAll(defeatedCards);
      }

      defeatedPlayer.setStillInGame(false);
      playerGameRepository.save(defeatedPlayer);

      winConditionService.checkWinConditions(game, attackerPlayer);
    }
  }

  @EventListener
  @Transactional
  public void endGameListener(GameOverEvent event) {
    Game game = event.getGame();
    PlayerGame winner = event.getWinner();

    game.setStatus(GameStatus.FINISHED.name());
    game.setWinner(winner);
    gameRepository.save(game);
  }

  // AUXILIARES ==================================

  private List<GameTerritory> distributeTerritories(
      Game game, List<PlayerGame> playerGames, List<Territory> allTerritories) {
    List<GameTerritory> gameTerritories = new java.util.ArrayList<>();
    int playerIndex = 0;

    for (Territory territory : allTerritories) {
      PlayerGame owner = playerGames.get(playerIndex % playerGames.size());

      GameTerritory gt = new GameTerritory();
      gt.setGame(game);
      gt.setTerritory(territory);
      gt.setOwner(owner);
      gt.setStaticArmies(1);
      gt.setMovedInArmies(0);
      gt.setUnallocatedArmies(0);

      gameTerritories.add(gt);

      playerIndex++;
    }
    return gameTerritories;
  }

  private void distributeObjectivesSmartly(List<PlayerGame> playerGames) {
    List<Objective> allObjectives = new ArrayList<>(objectiveRepository.findAll());
    Collections.shuffle(allObjectives, random);

    Map<String, PlayerGame> colorToPlayerMap =
        playerGames.stream()
            .collect(
                Collectors.toMap(
                    PlayerGame::getColor, pg -> pg, (existing, replacement) -> existing));

    List<Objective> availableObjectives = new ArrayList<>(allObjectives);

    for (PlayerGame playerGame : playerGames) {
      Objective assignedObjective = null;

      // Filtra objetivos válidos para o jogador atual
      List<Objective> validObjectives =
          availableObjectives.stream()
              .filter(
                  obj ->
                      !"ELIMINATE_PLAYER".equals(obj.getType())
                          || isEliminationObjectiveValid(obj, playerGame, colorToPlayerMap))
              .collect(Collectors.toList());

      if (!validObjectives.isEmpty()) {
        // Sorteia entre os válidos
        assignedObjective = validObjectives.get(random.nextInt(validObjectives.size()));
        availableObjectives.remove(assignedObjective);
      } else if (!availableObjectives.isEmpty()) {
        // Se não há válidos, pega qualquer um (garante que não trava)
        assignedObjective = availableObjectives.remove(0);
      }

      playerGame.setObjective(assignedObjective);
    }
  }

  private boolean isEliminationObjectiveValid(
      Objective objective, PlayerGame playerGame, Map<String, PlayerGame> colorToPlayerMap) {

    String description = objective.getDescription();
    String targetColor = extractTargetColorFromObjective(description);

    if (targetColor == null) {
      return false;
    }

    if (!colorToPlayerMap.containsKey(targetColor)) {
      return false;
    }

    PlayerGame targetPlayer = colorToPlayerMap.get(targetColor);
    if (targetPlayer.getId().equals(playerGame.getId())) {
      return false;
    }

    return true;
  }

  private String extractTargetColorFromObjective(String description) {
    if (description == null || description.isEmpty()) {
      return null;
    }

    Map<String, String> colorMapping =
        Map.of(
            "verdes", "green",
            "azuis", "blue",
            "vermelhos", "red",
            "amarelos", "#bfa640",
            "pretos", "black",
            "roxos", "purple");

    String descriptionLower = description.toLowerCase();

    for (Map.Entry<String, String> entry : colorMapping.entrySet()) {
      if (descriptionLower.contains(entry.getKey())) {
        return entry.getValue();
      }
    }

    return null;
  }

  private int calculateInitialTroops(int playerCount) {
    if (playerCount == 2) return 40;
    if (playerCount == 3) return 35;
    if (playerCount == 4) return 30;
    if (playerCount == 5) return 25;
    if (playerCount == 6) return 20;
    return 0;
  }

  private int calculateCardBonus(Game game) {
    int count = game.getCardSetExchangeCount();

    if (count == 0) return 4;
    if (count == 1) return 6;
    if (count == 2) return 8;
    if (count == 3) return 10;
    if (count == 4) return 12;

    // 6ª troca em diante: 15, 20, 25, 30
    // Fórmula: 15 + (exchangeCount - 5) * 5
    return 15 + (count - 5) * 5;
  }

  private boolean isTradeSetValid(List<Card> cardsToTrade) {
    long wildCount = cardsToTrade.stream().filter(c -> c.getType() == CardType.WILD).count();

    List<CardType> nonWilds =
        cardsToTrade.stream()
            .map(Card::getType)
            .filter(t -> t != CardType.WILD)
            .collect(Collectors.toList());

    if (nonWilds.size() == 3 && nonWilds.stream().distinct().count() == 1) {
      return true;
    }

    if (nonWilds.size() == 3 && nonWilds.stream().distinct().count() == 3) {
      return true;
    }

    if (wildCount > 0) {
      if (wildCount == 1) {
        if (nonWilds.stream().distinct().count() == 1) return true;
        if (nonWilds.stream().distinct().count() == 2) return true;
      }

      if (wildCount == 2) return true;

      if (wildCount == 3) return true;
    }

    return false;
  }

  private int calculateTerritoryMatchBonus(
      Game game, PlayerGame playerGame, List<Card> cardsToTrade) {
    int bonus = 0;

    // Buscar todos os GameTerritories que o jogador possui nesta partida
    List<GameTerritory> ownedGameTerritories =
        gameTerritoryRepository.findByGameAndOwner(game, playerGame);

    Map<Long, GameTerritory> ownedTerritoriesMap =
        ownedGameTerritories.stream()
            .collect(Collectors.toMap(gt -> gt.getTerritory().getId(), gt -> gt));

    // Verificar o bônus de correspondência de território
    for (Card card : cardsToTrade) {
      if (card.getTerritory() == null) continue;
      Long territoryMasterId = card.getTerritory().getId();

      if (ownedTerritoriesMap.containsKey(territoryMasterId)) {
        GameTerritory gt = ownedTerritoriesMap.get(territoryMasterId);
        gt.setStaticArmies(gt.getStaticArmies() + 2);
        gameTerritoryRepository.save(gt);
        bonus += 2;
        System.out.println("Bônus de território para a carta: " + card.getTerritory().getName());
      }
    }
    return bonus;
  }

  private void drawCard(PlayerGame playerGame) {
    Card cardToDraw =
        cardRepository
            .findRandomUnownedCard()
            .orElseThrow(
                () ->
                    new RuntimeException(
                        "Baralho de cartas vazio. Não foi possível comprar carta."));

    PlayerCard playerCard = new PlayerCard();
    playerCard.setPlayerGame(playerGame);
    playerCard.setCard(cardToDraw);

    playerCardRepository.save(playerCard);
  }

  private List<Integer> simulateDiceRolls(int count) {
    List<Integer> rolls = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      rolls.add(random.nextInt(6) + 1);
    }
    Collections.sort(rolls, Collections.reverseOrder());
    return rolls;
  }

  private int[] resolveCombat(List<Integer> attackRolls, List<Integer> defenseRolls) {
    int attackerLosses = 0;
    int defenderLosses = 0;

    int comparisons = Math.min(attackRolls.size(), defenseRolls.size());

    for (int i = 0; i < comparisons; i++) {
      int attackValue = attackRolls.get(i);
      int defenseValue = defenseRolls.get(i);

      if (attackValue > defenseValue) {
        // Atacante vence o confronto
        defenderLosses++;
      } else {
        // Defensor vence ou empata (empate é sempre do defensor)
        attackerLosses++;
      }
    }

    return new int[] {attackerLosses, defenderLosses};
  }

  public Game findGameById(Long gameId) {
    return gameRepository
        .findById(gameId)
        .orElseThrow(() -> new RuntimeException("Partida com ID " + gameId + " não encontrada."));
  }

  @Transactional(readOnly = true)
  public List<PlayerCard> getPlayerCards(PlayerGame pg) {
    return playerCardRepository.findByPlayerGame(pg);
  }

  // Decide a ação da IA baseada na fase.
  @Transactional
  public Game executeAIAction(Long gameId, String aiUsername) {
    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new RuntimeException("Partida não encontrada."));

    GameStatus status;
    try {
      status = GameStatus.valueOf(game.getStatus());
    } catch (IllegalArgumentException e) {
      System.err.println("IA - Status de jogo inválido: " + game.getStatus());
      return game;
    }

    System.out.println("======= IA - Iniciando ação =======");

    switch (status) {
      case SETUP_ALLOCATION:
      case REINFORCEMENT:
        System.out.println("IA - Fase de alocação inicial ou reforço");
        return handleAICardTradePhase(game, aiUsername);

      case ATTACK:
        System.out.println("IA - Fase de ataque");
        return handleAIAttackPhase(game, aiUsername);

      case MOVEMENT:
        System.out.println("IA - Fase de movimentação");
        return handleAIMovementPhase(game, aiUsername);

      default:
        System.err.println("IA - Fase inesperada (Enum): " + status);
        return game;
    }
  }

  // Alocação
  private Game handleAIReinforceAndSetupPhase(Game game, String aiUsername) {

    // Carregar Dados
    final Player aiPlayer =
        game.getPlayers().stream()
            .filter(p -> p.getUsername().equals(aiUsername))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Jogador IA não encontrado."));

    PlayerGame aiPlayerGame =
        playerGameRepository
            .findByGameAndPlayer(game, aiPlayer)
            .orElseThrow(() -> new RuntimeException("PlayerGame da IA não encontrado."));

    // --- LOOP SÍNCRONO DE ALOCAÇÃO ---

    while (true) {
      if (aiPlayerGame.getUnallocatedArmies() <= 0) {
        break;
      }

      System.out.println("IA - Alocando tropa. Restantes: " + aiPlayerGame.getUnallocatedArmies());

      final PlayerGame currentPlayerGame = aiPlayerGame;

      // Obter Territórios e Estratégia
      Set<GameTerritory> aiTerritories =
          game.getGameTerritories().stream()
              .filter(gt -> gt.getOwner() != null && gt.getOwner().equals(currentPlayerGame))
              .collect(Collectors.toSet());

      GameTerritory bestTarget = findMostVulnerableTerritory(aiTerritories);

      // Fallback: Se não houver fronteira pegue qualquer um.
      if (bestTarget == null) {
        bestTarget = aiTerritories.stream().findAny().orElse(null);
        if (bestTarget == null) {
          System.err.println("IA não tem territórios para alocar. Saindo do loop.");
          break; // Sai do loop para a transição de fase/turno.
        }
      }

      // Execução da Ação
      try {
        Long territoryId = bestTarget.getTerritory().getId();
        int troopsToAllocate = 1;

        System.out.println("IA - ALOCANDO 1 tropa em: " + bestTarget.getTerritory().getName());

        game = this.allocateTroops(game.getId(), aiUsername, territoryId, troopsToAllocate);
      } catch (Exception e) {
        System.err.println("Erro na alocação da IA. Parando: " + e.getMessage());
        break;
      }

      aiPlayerGame =
          playerGameRepository
              .findByGameAndPlayer(game, aiPlayer)
              .orElseThrow(
                  () -> new RuntimeException("PlayerGame da IA não encontrado após alocação."));
    }

    // Se a fase for REINFORCEMENT e zerou as tropas, passamos para ATTACK
    if (GameStatus.ATTACK.name().equals(game.getStatus())) {
      System.out.println("======= IA - Ação de reforço encerrada =======");
      return this.executeAIAction(game.getId(), aiUsername);
    }

    return game;
  }

  private GameTerritory findMostVulnerableTerritory(Set<GameTerritory> aiTerritories) {

    GameTerritory mostVulnerable = null;
    double highestVulnerabilityScore = -1.0;

    for (GameTerritory gt : aiTerritories) {
      // Encontrar o número de vizinhos inimigos
      long enemyNeighbors =
          gt.getTerritory().getNeighborTerritories().stream()
              .map(
                  neighborTerritory ->
                      gt.getGame().getGameTerritories().stream()
                          .filter(
                              gtN -> gtN.getTerritory().getId().equals(neighborTerritory.getId()))
                          .findFirst()
                          .orElse(null))
              .filter(
                  gtNeighbor ->
                      gtNeighbor != null
                          && gtNeighbor.getOwner() != null
                          && !gtNeighbor.getOwner().equals(gt.getOwner()))
              .count();

      if (enemyNeighbors == 0) continue; // Não é fronteira

      // Cálculo do Score de Vulnerabilidade (quanto maior, mais tropas são necessárias) Score =
      // (Total de Vizinhos Inimigos) / (Tropas Próprias)
      double currentVulnerabilityScore = (double) enemyNeighbors / gt.getStaticArmies();

      if (currentVulnerabilityScore > highestVulnerabilityScore) {
        highestVulnerabilityScore = currentVulnerabilityScore;
        mostVulnerable = gt;
      }
    }

    return mostVulnerable;
  }

  // Ataque
  private Game handleAIAttackPhase(Game game, String aiUsername) {
    // Carregar Dados
    final Player aiPlayer =
        game.getPlayers().stream()
            .filter(p -> p.getUsername().equals(aiUsername))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Jogador IA não encontrado."));

    PlayerGame aiPlayerGame =
        playerGameRepository
            .findByGameAndPlayer(game, aiPlayer)
            .orElseThrow(() -> new RuntimeException("PlayerGame da IA não encontrado."));

    // --- LOOP SÍNCRONO DE ATAQUE ---
    boolean continueAttacking = true;

    while (continueAttacking) {

      // DECISÃO DE ATAQUE
      AttackDecision decision = findBestAttack(game, aiPlayerGame);

      if (decision == null) {
        System.out.println("IA - Não há mais ataques bons. Passando a fase de ATAQUE.");
        continueAttacking = false;
        break;
      }

      // Execução da Ação
      try {
        Long sourceTerritoryId = decision.fromTerritoryId();
        Long targetTerritoryId = decision.toTerritoryId();
        int numDice = decision.numDice();

        System.out.println(
            "IA - ATACANDO "
                + targetTerritoryId
                + " de "
                + sourceTerritoryId
                + " com "
                + numDice
                + " dados.");

        // Chama método de ataque
        this.executeAIAttack(
            game.getId(), aiUsername, sourceTerritoryId, targetTerritoryId, numDice);

        game =
            gameRepository
                .findById(game.getId())
                .orElseThrow(() -> new RuntimeException("Partida não encontrada."));
      } catch (RuntimeException e) {
        System.err.println("IA falhou ao executar o ataque. Parando: " + e.getMessage());
        continueAttacking = false;
      }
    }

    return this.startNextTurn(game.getId(), aiUsername);
  }

  private AttackDecision findBestAttack(Game game, PlayerGame aiPlayerGame) {

    // Obter todos os territórios da IA que podem atacar (têm > 1 exército)
    Set<GameTerritory> attackSources =
        game.getGameTerritories().stream()
            .filter(gt -> gt.getOwner() != null && gt.getOwner().equals(aiPlayerGame))
            .filter(gt -> gt.getStaticArmies() > 1)
            .collect(Collectors.toSet());

    AttackDecision bestDecision = null;
    double highestScore = 0.0;

    // Iterar sobre todos os territórios de origem
    for (GameTerritory source : attackSources) {

      // Iterar sobre todos os vizinhos inimigos
      Set<GameTerritory> enemyNeighbors = findEnemyNeighbors(game, source, aiPlayerGame);

      for (GameTerritory target : enemyNeighbors) {

        // Calcular os Dados e o Score
        int attackingArmies = source.getStaticArmies() - 1;
        int defendingArmies = target.getStaticArmies();

        int numDice = Math.min(3, attackingArmies);

        double currentScore = (double) attackingArmies / defendingArmies;

        if (defendingArmies == 1) {
          currentScore *= 2.0;
        }

        if (currentScore > highestScore && currentScore >= 1.5) {
          highestScore = currentScore;
          bestDecision =
              new AttackDecision(
                  source.getTerritory().getId(), target.getTerritory().getId(), numDice);
        }
      }
    }

    return bestDecision;
  }

  private Set<GameTerritory> findEnemyNeighbors(
      Game game, GameTerritory source, PlayerGame aiPlayerGame) {
    return source.getTerritory().getNeighborTerritories().stream()
        .map(
            neighborTerritory ->
                game.getGameTerritories().stream()
                    .filter(gtN -> gtN.getTerritory().getId().equals(neighborTerritory.getId()))
                    .findFirst()
                    .orElse(null))
        .filter(
            gtNeighbor ->
                gtNeighbor != null
                    && gtNeighbor.getOwner() != null
                    && !gtNeighbor.getOwner().equals(aiPlayerGame))
        .collect(Collectors.toSet());
  }

  private AttackResult executeAIAttack(
      Long gameId,
      String aiUsername,
      Long sourceTerritoryId,
      Long targetTerritoryId,
      int attackDiceCount) {
    AttackRequestDto dto =
        new AttackRequestDto(sourceTerritoryId, targetTerritoryId, attackDiceCount);

    return this.attackTerritory(gameId, aiUsername, dto);
  }

  private record AttackDecision(Long fromTerritoryId, Long toTerritoryId, int numDice) {}

  // Movimentação
  private Game handleAIMovementPhase(Game game, String aiUsername) {
    // Carregar Jogador
    final Player aiPlayer =
        game.getPlayers().stream()
            .filter(p -> p.getUsername().equals(aiUsername))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Jogador IA não encontrado."));

    PlayerGame aiPlayerGame =
        playerGameRepository
            .findByGameAndPlayer(game, aiPlayer)
            .orElseThrow(() -> new RuntimeException("PlayerGame da IA não encontrado."));

    // Obter Territórios da IA (necessário para as estratégias)
    Set<GameTerritory> aiTerritories =
        game.getGameTerritories().stream()
            .filter(gt -> gt.getOwner() != null && gt.getOwner().equals(aiPlayerGame))
            .collect(Collectors.toSet());

    // Decisão Estratégica
    GameTerritory source = findBestFortificationSource(aiTerritories, aiPlayerGame);
    GameTerritory target = findBestFortificationTarget(aiTerritories, aiPlayerGame);

    // Calcular Quantidade e Validar Condições
    int troopsToMove = 0;
    boolean moveExecuted = false;

    if (source != null && target != null && !source.getId().equals(target.getId())) {

      // Validação de Vizinhança
      boolean isAdjacent =
          territoryBorderRepository
              .findByTerritoryIds(source.getTerritory().getId(), target.getTerritory().getId())
              .isPresent();

      if (isAdjacent) {
        // Lógica de Cálculo de Tropas
        troopsToMove = source.getStaticArmies() / 2;
        int maxMoveable = source.getStaticArmies() - 1;
        troopsToMove = Math.min(troopsToMove, maxMoveable);
      }
    }

    // Execução
    if (troopsToMove >= 1) {
      try {
        Long sourceTerritoryId = source.getTerritory().getId();
        Long targetTerritoryId = target.getTerritory().getId();

        System.out.println(
            "IA - FORTIFICANDO: "
                + troopsToMove
                + " de "
                + source.getTerritory().getName()
                + " para "
                + target.getTerritory().getName());

        this.executeAIMovement(
            game.getId(), aiUsername, sourceTerritoryId, targetTerritoryId, troopsToMove);
        moveExecuted = true;

      } catch (Exception e) {
        System.err.println("IA falhou ao executar a fortificação: " + e.getMessage());
      }
    }

    // Fim do Turno
    System.out.println("IA - Fim do Turno. Passando para o próximo jogador.");

    return this.startNextTurn(game.getId(), aiUsername);
  }

  private GameTerritory findBestFortificationSource(
      Set<GameTerritory> aiTerritories, PlayerGame aiPlayerGame) {
    // Filtra por territórios internos (que não fazem fronteira com o inimigo)
    // Ordena pelo maior número de tropas.
    return aiTerritories.stream()
        .filter(gt -> isBorderTerritory(gt, aiPlayerGame.getId(), gt.getGame()) == false)
        .max(Comparator.comparing(GameTerritory::getStaticArmies))
        .orElse(null);
  }

  private GameTerritory findBestFortificationTarget(
      Set<GameTerritory> aiTerritories, PlayerGame aiPlayerGame) {
    // Filtra por territórios que são fronteira com o inimigo.
    // Ordena pelo menor número de tropas.
    return aiTerritories.stream()
        .filter(gt -> isBorderTerritory(gt, aiPlayerGame.getId(), gt.getGame()))
        .min(Comparator.comparing(GameTerritory::getStaticArmies))
        .orElse(null);
  }

  private boolean isBorderTerritory(GameTerritory gt, Long aiPlayerGameId, Game game) {
    return gt.getTerritory().getNeighborTerritories().stream()
        .map(
            neighborTerritory ->
                game.getGameTerritories().stream()
                    .filter(gtN -> gtN.getTerritory().getId().equals(neighborTerritory.getId()))
                    .findFirst()
                    .orElse(null))
        .filter(gtNeighbor -> gtNeighbor != null && gtNeighbor.getOwner() != null)
        .anyMatch(gtNeighbor -> !gtNeighbor.getOwner().getId().equals(aiPlayerGameId));
  }

  private Game executeAIMovement(
      Long gameId,
      String aiUsername,
      Long sourceTerritoryId,
      Long targetTerritoryId,
      int troopsToMove) {
    return this.moveTroops(gameId, aiUsername, sourceTerritoryId, targetTerritoryId, troopsToMove);
  }

  // Cartas
  private Game handleAICardTradePhase(Game game, String aiUsername) {

    // Carregar Jogador e Cartas
    final Player aiPlayer =
        game.getPlayers().stream()
            .filter(p -> p.getUsername().equals(aiUsername))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Jogador IA não encontrado."));

    PlayerGame aiPlayerGame =
        playerGameRepository
            .findByGameAndPlayer(game, aiPlayer)
            .orElseThrow(() -> new RuntimeException("PlayerGame da IA não encontrado."));

    Set<PlayerCard> playerCards = aiPlayerGame.getPlayerCards();

    // Condição de Saída
    if (playerCards == null || playerCards.size() < 3) {
      System.out.println("IA - Sem cartas suficientes para trocar. Iniciando Reforço.");
      return handleAIReinforceAndSetupPhase(game, aiUsername);
    }

    // DECISÃO DE TROCA
    List<Long> cardIdsToTrade = findBestCardSet(playerCards);

    // Execução
    if (cardIdsToTrade.size() == 3) {
      try {
        System.out.println("IA - Trocando um conjunto de cartas.");

        game = this.executeAICardTrade(game.getId(), aiUsername, cardIdsToTrade);

        // Se a IA fez a troca e ainda tem 5+ cartas, ela deve tentar trocar novamente.
        aiPlayerGame =
            playerGameRepository
                .findByGameAndPlayer(game, aiPlayer)
                .orElseThrow(
                    () -> new RuntimeException("PlayerGame da IA não encontrado após troca."));

        if (aiPlayerGame.getPlayerCards().size() >= 5) {
          return handleAICardTradePhase(game, aiUsername);
        }

      } catch (RuntimeException e) {
        System.err.println("IA falhou ao executar a troca de cartas: " + e.getMessage());
      }
    } else {
      System.out.println("IA - Não encontrou um conjunto de cartas trocável. Iniciando Reforço.");
    }

    // Transição para a Fase de Reforço
    return handleAIReinforceAndSetupPhase(game, aiUsername);
  }

  private List<Long> findBestCardSet(Set<PlayerCard> playerCards) {
    if (playerCards.size() < 3) {
      return List.of();
    }

    // Agrupa as cartas por tipo
    Map<CardType, List<PlayerCard>> cardsByType =
        playerCards.stream().collect(Collectors.groupingBy(pc -> pc.getCard().getType()));

    // Tenta encontrar 3 cartas do mesmo tipo
    for (Map.Entry<CardType, List<PlayerCard>> entry : cardsByType.entrySet()) {
      if (entry.getValue().size() >= 3) {
        return entry.getValue().stream()
            .limit(3)
            .map(PlayerCard::getId)
            .collect(Collectors.toList());
      }
    }

    // Tenta encontrar 3 cartas de tipos diferentes
    List<PlayerCard> infantry = cardsByType.getOrDefault(CardType.INFANTRY, List.of());
    List<PlayerCard> cavalry = cardsByType.getOrDefault(CardType.CAVALRY, List.of());
    List<PlayerCard> artillery = cardsByType.getOrDefault(CardType.CANNON, List.of());

    if (infantry.size() >= 1 && cavalry.size() >= 1 && artillery.size() >= 1) {
      // Encontrou 1 de cada.
      return List.of(infantry.get(0).getId(), cavalry.get(0).getId(), artillery.get(0).getId());
    }

    return List.of();
  }

  private Game executeAICardTrade(Long gameId, String aiUsername, List<Long> cardIds) {
    return this.tradeCardsForReinforcements(gameId, aiUsername, cardIds);
  }
}
