package com.nageoffer.shortlink.aigateway.routing;

import com.nageoffer.shortlink.aigateway.config.AiGatewayProperties;
import com.nageoffer.shortlink.aigateway.config.AiGatewayRoutingProperties;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayClientException;
import com.nageoffer.shortlink.aigateway.exception.AiGatewayErrorCode;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderGroupBindingEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderGroupEntity;
import com.nageoffer.shortlink.aigateway.persistence.entity.ProviderGroupMemberEntity;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderGroupBindingRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderGroupMemberRepository;
import com.nageoffer.shortlink.aigateway.persistence.repository.ProviderGroupRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 通道组：一组互为备份的 provider，以及组内的负载均衡策略。
 * <p>
 * 与租户配置一致，采用"DB 为真源 + 内存快照"的读路径：路由在每请求热路径上只读内存，
 * 变更由管理面写库后触发快照刷新。DB 不可用（本地 mock 链路、未配数据源）时回退到 yml 种子，
 * 这样"分组"不会成为启动的硬依赖。
 */
@Slf4j
@Service
public class ProviderGroupService {

    private static final String SOURCE_DATABASE = "database";

    private static final String SOURCE_YML = "yml";

    private final AiGatewayProperties properties;

    private final ProviderHealthScoreService providerHealthScoreService;

    private final ObjectProvider<ProviderGroupRepository> groupRepository;

    private final ObjectProvider<ProviderGroupMemberRepository> memberRepository;

    private final ObjectProvider<ProviderGroupBindingRepository> bindingRepository;

    private final ChannelHealthView channelHealthView;

    /**
     * 轮询游标：多实例部署时各实例独立计数。
     * <p>
     * 语义上仍是"每个实例把请求依次摊到组内成员"，整体流量依然分散，
     * 只是无法保证全局严格轮转——为此引入一次同步 Redis 往返不值得（会让路由阻塞事件循环）。
     */
    private final Map<String, AtomicInteger> roundRobinCursor = new ConcurrentHashMap<>();

    private volatile GroupSnapshot snapshot = GroupSnapshot.empty();

    private volatile String snapshotSource = SOURCE_YML;

    @Autowired
    public ProviderGroupService(AiGatewayProperties properties,
                                ProviderHealthScoreService providerHealthScoreService,
                                ObjectProvider<ProviderGroupRepository> groupRepository,
                                ObjectProvider<ProviderGroupMemberRepository> memberRepository,
                                ObjectProvider<ProviderGroupBindingRepository> bindingRepository,
                                ChannelHealthView channelHealthView) {
        this.properties = properties;
        this.providerHealthScoreService = providerHealthScoreService;
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.bindingRepository = bindingRepository;
        this.channelHealthView = channelHealthView;
        // 先用 yml 种子填好快照，构造完即可路由；DB 快照在 init 里异步覆盖
        this.snapshot = fromYml();
    }

    /**
     * 不消费渠道健康状态的构造方式：等价于探测未启用时的旧行为。
     */
    public ProviderGroupService(AiGatewayProperties properties,
                                ProviderHealthScoreService providerHealthScoreService,
                                ObjectProvider<ProviderGroupRepository> groupRepository,
                                ObjectProvider<ProviderGroupMemberRepository> memberRepository,
                                ObjectProvider<ProviderGroupBindingRepository> bindingRepository) {
        this(properties, providerHealthScoreService, groupRepository, memberRepository, bindingRepository,
                ChannelHealthView.allowAll());
    }

    /**
     * 仅使用 yml 种子的构造方式（测试与无 DB 场景）。
     */
    public ProviderGroupService(AiGatewayProperties properties, ProviderHealthScoreService providerHealthScoreService) {
        this(properties, providerHealthScoreService, null, null, null);
    }

    @PostConstruct
    void init() {
        refresh().subscribe(ignored -> {
        }, ex -> log.warn("failed to load provider groups from database, keep yml seed: {}", ex.getMessage()));
    }

    /**
     * 从 DB 重新加载内存快照。
     * <p>
     * 表里一条组都没有时保留 yml 种子：否则一次误清空就会让所有模型失去分组路由。
     */
    public Mono<Void> refresh() {
        ProviderGroupRepository groups = available(groupRepository);
        ProviderGroupMemberRepository members = available(memberRepository);
        ProviderGroupBindingRepository bindings = available(bindingRepository);
        if (groups == null || members == null || bindings == null) {
            this.snapshot = fromYml();
            this.snapshotSource = SOURCE_YML;
            return Mono.empty();
        }
        return Mono.zip(groups.findAll().collectList(), members.findAll().collectList(), bindings.findAll().collectList())
                .doOnNext(tuple -> {
                    if (tuple.getT1().isEmpty()) {
                        this.snapshot = fromYml();
                        this.snapshotSource = SOURCE_YML;
                        return;
                    }
                    this.snapshot = fromDatabase(tuple.getT1(), tuple.getT2(), tuple.getT3());
                    this.snapshotSource = SOURCE_DATABASE;
                })
                .then();
    }

    /**
     * 按模型选出通道组与组内顺序；未绑定、组已停用或组内无可用成员时返回 {@code null}（走默认路由）。
     * <p>
     * 这是路由热路径上唯一的入口，只读内存快照。
     */
    public GroupSelection selectForModel(String model) {
        if (!StringUtils.hasText(model)) {
            return null;
        }
        GroupDefinition group = snapshot.groups().get(snapshot.bindings().get(model));
        if (group == null || !group.enabled()) {
            return null;
        }
        List<GroupMember> ordered = orderMembers(group, eligibleMembers(group), model);
        if (ordered.isEmpty()) {
            return null;
        }
        return new GroupSelection(group.name(), group.strategy(), ordered);
    }

    /**
     * 指定组的成员顺序（第一个是主通道）；组不存在时返回空列表。
     */
    public List<GroupMember> orderedMembers(String groupName, String model) {
        GroupDefinition group = snapshot.groups().get(groupName);
        if (group == null) {
            return List.of();
        }
        return orderMembers(group, eligibleMembers(group), model);
    }

    public Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", snapshotSource);
        result.put("groups", snapshot.groups().values().stream().map(this::describeGroup).toList());
        result.put("bindings", new LinkedHashMap<>(snapshot.bindings()));
        return result;
    }

    /**
     * 路由预览：这个模型最终会走哪条通道，以及回退顺序。
     */
    public Map<String, Object> preview(String model) {
        GroupSelection selection = selectForModel(model);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
        if (selection == null) {
            result.put("group", null);
            result.put("strategy", null);
            result.put("routeSource", "default");
            result.put("order", List.of());
            return result;
        }
        result.put("group", selection.groupName());
        result.put("strategy", selection.strategy().name());
        result.put("routeSource", routeSourceOf(selection.strategy()));
        result.put("order", selection.order().stream().map(member -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", member.provider());
            row.put("model", member.model());
            row.put("weight", member.weight());
            row.put("priority", member.priority());
            return row;
        }).toList());
        return result;
    }

    /**
     * 新建或覆盖一个通道组（成员整体替换，避免增量合并带来的脏数据）。
     */
    public Mono<Map<String, Object>> saveGroup(String groupName,
                                               String strategy,
                                               Boolean enabled,
                                               String description,
                                               List<MemberSpec> members) {
        String normalizedName = requireText(groupName, "组名不能为空");
        AiGatewayRoutingProperties.LoadBalanceStrategy parsedStrategy = parseStrategy(strategy);
        List<MemberSpec> normalizedMembers = normalizeMembers(members);
        if (normalizedMembers.isEmpty()) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "通道组至少要有一个成员");
        }

        ProviderGroupEntity groupEntity = new ProviderGroupEntity();
        groupEntity.setGroupName(normalizedName);
        groupEntity.setStrategy(parsedStrategy.name());
        groupEntity.setEnabled(enabled == null || enabled);
        groupEntity.setDescription(description);
        groupEntity.setUpdatedAt(LocalDateTime.now());

        List<ProviderGroupMemberEntity> memberEntities = normalizedMembers.stream().map(spec -> {
            ProviderGroupMemberEntity entity = new ProviderGroupMemberEntity();
            entity.setGroupName(normalizedName);
            entity.setProvider(spec.provider());
            entity.setModel(spec.model());
            entity.setWeight(spec.weight());
            entity.setPriority(spec.priority());
            entity.setEnabled(true);
            return entity;
        }).toList();

        return requiringDatabase("保存通道组")
                .flatMap(ignored -> available(memberRepository).deleteByGroupName(normalizedName)
                        .thenMany(available(memberRepository).saveAll(memberEntities))
                        .then(available(groupRepository).findByGroupName(normalizedName)
                                .flatMap(existing -> {
                                    groupEntity.setId(existing.getId());
                                    return available(groupRepository).save(groupEntity);
                                })
                                .switchIfEmpty(Mono.defer(() -> available(groupRepository).save(groupEntity))))
                        .then())
                .then(refresh())
                .then(Mono.fromSupplier(this::describe));
    }

    /**
     * 删除通道组，并解除其上的模型绑定。
     */
    public Mono<Void> deleteGroup(String groupName) {
        String normalizedName = requireText(groupName, "组名不能为空");
        return requiringDatabase("删除通道组")
                .flatMap(ignored -> available(memberRepository).deleteByGroupName(normalizedName)
                        .then(available(bindingRepository).deleteByGroupName(normalizedName))
                        .then(available(groupRepository).deleteByGroupName(normalizedName)))
                .then(refresh());
    }

    /**
     * 把模型绑定到通道组，一个模型只归属一个组。
     */
    public Mono<Map<String, Object>> bindModels(String groupName, List<String> models) {
        String normalizedName = requireText(groupName, "组名不能为空");
        if (models == null || models.isEmpty()) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "模型列表不能为空");
        }
        List<String> normalizedModels = models.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        List<ProviderGroupBindingEntity> bindings = normalizedModels.stream().map(model -> {
            ProviderGroupBindingEntity entity = new ProviderGroupBindingEntity();
            entity.setModel(model);
            entity.setGroupName(normalizedName);
            entity.setUpdatedAt(LocalDateTime.now());
            return entity;
        }).toList();

        return requiringDatabase("绑定模型")
                .then(Mono.defer(() -> available(groupRepository).findByGroupName(normalizedName)
                        .switchIfEmpty(Mono.error(new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST,
                                "通道组不存在: " + normalizedName)))
                        // 先摘掉这些模型原有的绑定，保证"一个模型只归属一个组"
                        .flatMap(existing -> available(bindingRepository).findAll()
                                .filter(binding -> normalizedModels.contains(binding.getModel()))
                                .flatMap(binding -> available(bindingRepository).deleteById(binding.getId()))
                                .thenMany(available(bindingRepository).saveAll(bindings))
                                .then())))
                .then(refresh())
                .then(Mono.fromSupplier(this::describe));
    }

    public Mono<Map<String, Object>> unbindModel(String model) {
        String normalizedModel = requireText(model, "模型名不能为空");
        return requiringDatabase("解绑模型")
                .flatMap(ignored -> available(bindingRepository).deleteByModel(normalizedModel))
                .then(refresh())
                .then(Mono.fromSupplier(this::describe));
    }

    private List<GroupMember> orderMembers(GroupDefinition group, List<GroupMember> members, String model) {
        if (members.size() <= 1) {
            return members;
        }
        return switch (group.strategy()) {
            case PRIORITY -> byPriority(members);
            case DYNAMIC -> byHealth(members, model);
            case ROUND_ROBIN -> rotate(members, nextCursor(group.name()));
            case RANDOM -> rotate(members, ThreadLocalRandom.current().nextInt(members.size()));
            case WEIGHTED -> byWeight(members);
        };
    }

    /**
     * 优先级降级：优先级数字大的先上，同级按权重大的先上。
     */
    private List<GroupMember> byPriority(List<GroupMember> members) {
        List<GroupMember> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparingInt(GroupMember::priority).reversed()
                .thenComparing(Comparator.comparingInt(GroupMember::weight).reversed())
                .thenComparing(GroupMember::provider));
        return ordered;
    }

    /**
     * 动态：按健康分排序；没有足够调用数据的成员按优先级排在后面。
     */
    private List<GroupMember> byHealth(List<GroupMember> members, String model) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        if (providerHealthScoreService != null && StringUtils.hasText(model)) {
            providerHealthScoreService.getProviderScores(model)
                    .forEach(score -> scores.put(score.getProvider(), score.getHealthScore()));
        }
        List<GroupMember> ordered = new ArrayList<>(members);
        List<GroupMember> fallbackOrder = byPriority(members);
        ordered.sort(Comparator
                .comparingInt((GroupMember member) -> scores.getOrDefault(member.provider(), -1)).reversed()
                .thenComparingInt(member -> fallbackOrder.indexOf(member)));
        return ordered;
    }

    /**
     * 加权：按权重比例随机选主通道，其余按优先级降级。
     */
    private List<GroupMember> byWeight(List<GroupMember> members) {
        int totalWeight = members.stream().mapToInt(member -> Math.max(member.weight(), 1)).sum();
        int cursor = ThreadLocalRandom.current().nextInt(totalWeight);
        int primaryIndex = members.size() - 1;
        for (int i = 0; i < members.size(); i++) {
            cursor -= Math.max(members.get(i).weight(), 1);
            if (cursor < 0) {
                primaryIndex = i;
                break;
            }
        }
        GroupMember primary = members.get(primaryIndex);
        List<GroupMember> ordered = new ArrayList<>();
        ordered.add(primary);
        byPriority(members).stream().filter(member -> member != primary).forEach(ordered::add);
        return ordered;
    }

    private List<GroupMember> rotate(List<GroupMember> members, int start) {
        int size = members.size();
        int normalized = Math.floorMod(start, size);
        List<GroupMember> ordered = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ordered.add(members.get((normalized + i) % size));
        }
        return ordered;
    }

    private int nextCursor(String groupName) {
        return roundRobinCursor.computeIfAbsent(groupName, key -> new AtomicInteger()).getAndIncrement();
    }

    private List<GroupMember> eligibleMembers(GroupDefinition group) {
        return group.members().stream()
                .filter(member -> properties.getUpstream().getProviderBaseUrl().containsKey(member.provider()))
                // 被探测判为不可用（或人工禁用）的成员不参与组内轮转与降级链：
                // 组本身就是"这些通道互为备份"的声明，把一个已知坏掉的成员留在里面，
                // 只会让每次请求都先撞一次它再走 fallback
                .filter(member -> channelHealthView.allows(member.provider()))
                .toList();
    }

    private GroupSnapshot fromYml() {
        Map<String, GroupDefinition> groups = new LinkedHashMap<>();
        properties.getRouting().getProviderGroups().forEach((name, config) -> {
            if (config == null || !config.isEnabled()) {
                return;
            }
            List<GroupMember> members = (config.getMembers() == null ? List.<AiGatewayRoutingProperties.GroupMemberConfig>of() : config.getMembers())
                    .stream()
                    .filter(AiGatewayRoutingProperties.GroupMemberConfig::isEnabled)
                    .filter(member -> StringUtils.hasText(member.getProvider()))
                    .map(member -> new GroupMember(member.getProvider(), member.getModel(),
                            member.getWeight() == null ? 1 : member.getWeight(),
                            member.getPriority() == null ? 1 : member.getPriority()))
                    .toList();
            if (members.isEmpty()) {
                return;
            }
            groups.put(name, new GroupDefinition(name, config.getStrategy(), true, config.getDescription(), members));
        });
        return new GroupSnapshot(groups, new LinkedHashMap<>(properties.getRouting().getModelGroups()));
    }

    private GroupSnapshot fromDatabase(List<ProviderGroupEntity> groups,
                                       List<ProviderGroupMemberEntity> members,
                                       List<ProviderGroupBindingEntity> bindings) {
        Map<String, List<GroupMember>> membersByGroup = members.stream()
                .filter(member -> member.getEnabled() == null || member.getEnabled())
                .filter(member -> StringUtils.hasText(member.getProvider()))
                .collect(Collectors.groupingBy(ProviderGroupMemberEntity::getGroupName,
                        LinkedHashMap::new,
                        Collectors.mapping(member -> new GroupMember(
                                member.getProvider(),
                                member.getModel(),
                                member.getWeight() == null ? 1 : member.getWeight(),
                                member.getPriority() == null ? 1 : member.getPriority()), Collectors.toList())));

        Map<String, GroupDefinition> groupDefinitions = new LinkedHashMap<>();
        for (ProviderGroupEntity group : groups) {
            List<GroupMember> groupMembers = membersByGroup.getOrDefault(group.getGroupName(), List.of());
            groupDefinitions.put(group.getGroupName(), new GroupDefinition(
                    group.getGroupName(),
                    parseStrategy(group.getStrategy()),
                    group.getEnabled() == null || group.getEnabled(),
                    group.getDescription(),
                    groupMembers));
        }

        Map<String, String> bindingMap = new LinkedHashMap<>();
        for (ProviderGroupBindingEntity binding : bindings) {
            if (StringUtils.hasText(binding.getModel()) && StringUtils.hasText(binding.getGroupName())) {
                bindingMap.put(binding.getModel(), binding.getGroupName());
            }
        }
        return new GroupSnapshot(groupDefinitions, bindingMap);
    }

    private Map<String, Object> describeGroup(GroupDefinition group) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("groupName", group.name());
        row.put("strategy", group.strategy().name());
        row.put("enabled", group.enabled());
        row.put("description", group.description());
        row.put("members", group.members().stream().map(member -> {
            Map<String, Object> memberRow = new LinkedHashMap<>();
            memberRow.put("provider", member.provider());
            memberRow.put("model", member.model());
            memberRow.put("weight", member.weight());
            memberRow.put("priority", member.priority());
            return memberRow;
        }).toList());
        return row;
    }

    private AiGatewayRoutingProperties.LoadBalanceStrategy parseStrategy(String strategy) {
        if (!StringUtils.hasText(strategy)) {
            return AiGatewayRoutingProperties.LoadBalanceStrategy.PRIORITY;
        }
        try {
            return AiGatewayRoutingProperties.LoadBalanceStrategy.valueOf(strategy.trim().replace('-', '_').toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, "不支持的负载均衡策略: " + strategy);
        }
    }

    private List<MemberSpec> normalizeMembers(List<MemberSpec> members) {
        if (members == null) {
            return List.of();
        }
        return members.stream()
                .filter(member -> member != null && StringUtils.hasText(member.provider()))
                .map(member -> new MemberSpec(
                        member.provider().trim(),
                        StringUtils.hasText(member.model()) ? member.model().trim() : null,
                        member.weight() == null || member.weight() < 1 ? 1 : member.weight(),
                        member.priority() == null ? 1 : member.priority()))
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(MemberSpec::provider, member -> member, (first, second) -> second, LinkedHashMap::new),
                        map -> new ArrayList<>(map.values())));
    }

    private Mono<Void> requiringDatabase(String action) {
        if (available(groupRepository) == null || available(memberRepository) == null || available(bindingRepository) == null) {
            return Mono.error(new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST,
                    action + "需要数据库支持，当前未配置数据源"));
        }
        return Mono.empty();
    }

    private <T> T available(ObjectProvider<T> provider) {
        return provider == null ? null : provider.getIfAvailable();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new AiGatewayClientException(AiGatewayErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    /**
     * 路由来源标识，形如 {@code group-weighted}。
     */
    public static String routeSourceOf(AiGatewayRoutingProperties.LoadBalanceStrategy strategy) {
        return "group-" + strategy.name().toLowerCase().replace('_', '-');
    }

    /**
     * 组成员（不可变）。
     */
    public record GroupMember(String provider, String model, int weight, int priority) {
    }

    /**
     * 一次组选择的结果：命中哪个组、用的什么策略、成员顺序。
     */
    public record GroupSelection(String groupName, AiGatewayRoutingProperties.LoadBalanceStrategy strategy, List<GroupMember> order) {
    }

    /**
     * 管理面提交的成员定义。
     */
    public record MemberSpec(String provider, String model, Integer weight, Integer priority) {
    }

    private record GroupDefinition(String name,
                                   AiGatewayRoutingProperties.LoadBalanceStrategy strategy,
                                   boolean enabled,
                                   String description,
                                   List<GroupMember> members) {
    }

    private record GroupSnapshot(Map<String, GroupDefinition> groups, Map<String, String> bindings) {

        static GroupSnapshot empty() {
            return new GroupSnapshot(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }
}
