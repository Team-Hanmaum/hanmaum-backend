package com.hanmaum.backend.sharing.service;

import com.hanmaum.backend.careitem.dto.CareItemDetail;
import com.hanmaum.backend.careitem.service.CareItemQueryService;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.*;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.sharing.code.SharingErrorCode;
import com.hanmaum.backend.sharing.dto.*;
import com.hanmaum.backend.sharing.repository.ShareLinkRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ShareLinkService {
  private final CareSpaceAccessService access;
  private final CareItemQueryService items;
  private final ShareLinkRepository links;
  private final ShareLinkTokenService tokens;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final Clock clock;

  public ShareLinkService(
      CareSpaceAccessService access,
      CareItemQueryService items,
      ShareLinkRepository links,
      ShareLinkTokenService tokens,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      Clock clock) {
    this.access = access;
    this.items = items;
    this.links = links;
    this.tokens = tokens;
    this.receipts = receipts;
    this.digests = digests;
    this.clock = clock;
  }

  /**
   * Issues all selected targets atomically, serialized with source erasure and membership changes.
   */
  @Transactional
  public CreateShareLinkResponse create(UUID actor, UUID space, JsonNode body) {
    var request = CreateShareLinkRequest.parse(body);
    UUID key = request.clientRequestId();
    String kind = "CREATE_SHARE_LINK", scope = "SPACE:" + space;
    var parts = new ArrayList<>(List.of(actor.toString(), key.toString(), kind, scope));
    for (var target : request.targets()) {
      parts.add(target.itemId().toString());
      parts.add(Long.toString(target.expectedItemVersion()));
      parts.add(Boolean.toString(target.includesChange()));
    }
    String digest = digests.digest(parts.toArray(String[]::new));
    if (!receipts.tryLock(actor, key)) throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(actor, key);
    if (previous.isPresent()
        && (!kind.equals(previous.get().commandKind())
            || !scope.equals(previous.get().scopeKey())
            || !digest.equals(previous.get().requestDigest())))
      throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    var current = access.requireActiveMember(actor, space);
    if (previous.isPresent()) {
      var refs = previous.get().resultRefs();
      if (!current.membershipId().toString().equals(refs.get("membershipId")))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      var saved =
          links
              .byId(UUID.fromString(refs.get("shareLinkId")))
              .orElseThrow(ShareLinkService::missing);
      return response(saved, links.targets(saved.id()));
    }
    var ids = request.targets().stream().map(CreateShareLinkRequest.Target::itemId).toList();
    var details = index(items.sharingDetails(actor, space, ids));
    Set<UUID> changes =
        request.targets().stream().anyMatch(CreateShareLinkRequest.Target::includesChange)
            ? links.validChanges(space, ids)
            : Set.of();
    var targets = new ArrayList<ShareLinkRepository.Target>();
    for (var target : request.targets()) {
      var item = details.get(target.itemId());
      if (item == null) throw missing();
      if (!Long.toString(target.expectedItemVersion()).equals(item.version()))
        throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
      if (state(item) != SharedItemsResponse.State.AVAILABLE
          || (target.includesChange() && !changes.contains(item.itemId())))
        throw new ApiException(SharingErrorCode.SHARE_TARGET_UNAVAILABLE);
      targets.add(
          new ShareLinkRepository.Target(
              item.itemId(),
              item.itemType(),
              target.expectedItemVersion(),
              target.includesChange()));
    }
    UUID id = UUID.randomUUID();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var link =
        new ShareLinkRepository.Link(
            id,
            space,
            current.membershipId(),
            tokens.hash(tokens.token(id, space)),
            tokens.prefix(),
            now);
    links.insert(link, targets);
    receipts.saveSuccess(
        actor,
        key,
        new CommandReceipt(
            kind,
            scope,
            digest,
            Map.of(
                "membershipId", current.membershipId().toString(), "shareLinkId", id.toString())),
        now);
    return response(link, targets);
  }

  /**
   * Reads one consistent current snapshot, with full content suppressed for unavailable targets.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public SharedItemsResponse read(UUID actor, String token) {
    var link = links.byHash(tokens.hash(token)).orElseThrow(ShareLinkService::missing);
    var targets = links.targets(link.id());
    var details =
        index(
            items.sharingDetails(
                actor,
                link.space(),
                targets.stream().map(ShareLinkRepository.Target::item).toList()));
    var result = new ArrayList<SharedItemsResponse.Entry>();
    for (var target : targets) {
      var item = details.get(target.item());
      if (item == null) continue;
      var state = state(item);
      result.add(
          new SharedItemsResponse.Entry(
              item.itemId(), state, state == SharedItemsResponse.State.AVAILABLE ? item : null));
    }
    if (result.isEmpty()) throw missing();
    return new SharedItemsResponse(link.space(), List.copyOf(result));
  }

  /** Replays safe card metadata using the original prefix; tokens never enter receipts or logs. */
  private CreateShareLinkResponse response(
      ShareLinkRepository.Link link, List<ShareLinkRepository.Target> targets) {
    if (targets.isEmpty()) throw missing();
    String token = tokens.token(link.id(), link.space());
    if (!tokens.hash(token).equals(link.hash())) throw missing();
    String url = link.prefix() + token;
    return new CreateShareLinkResponse(
        link.id(),
        url,
        targets.stream()
            .map(
                target ->
                    new CreateShareLinkResponse.Card(target.type(), target.includesChange(), url))
            .toList());
  }

  private static Map<UUID, CareItemDetail> index(List<CareItemDetail> details) {
    return details.stream().collect(Collectors.toMap(CareItemDetail::itemId, Function.identity()));
  }

  private static SharedItemsResponse.State state(CareItemDetail item) {
    if ("DELETED".equals(item.visibility())) return SharedItemsResponse.State.DELETED;
    return item.needsReconfirmation()
        ? SharedItemsResponse.State.NEEDS_RECONFIRMATION
        : SharedItemsResponse.State.AVAILABLE;
  }

  private static ApiException missing() {
    return new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
  }
}
