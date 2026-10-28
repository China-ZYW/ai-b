package com.aibi.module.org.mapper;

import com.aibi.module.org.entity.TeamMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 团队成员 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p><b>务必注意</b>：{@code team_member} 带 {@code tenant_id} 却列入租户拦截器 IGNORE 名单
 * （登录链路要在租户上下文建立之前按 {@code uid} 反查所属团队），因此本 Mapper 的每条 SQL
 * 都<b>显式携带 {@code tenant_id}</b>，这是该表唯一的隔离保障，新增查询漏写即造成跨组织越权。
 *
 * <p>成员列表一律带 {@code status = 1}：已移除成员的行会保留以留痕（审计与重新邀请需要历史归属），
 * 只判断"行是否存在"会把已移除的人当成在册成员。
 */
public interface TeamMemberMapper extends BaseMapper<TeamMember> {

    /** 某身份在本组织的全部在册团队成员关系（登录时解析 teamIds 与团队角色）。 */
    @Select("""
            SELECT * FROM team_member
            WHERE tenant_id = #{tenantId} AND uid = #{uid} AND status = 1
            ORDER BY team_id ASC
            """)
    List<TeamMember> selectActiveByUid(@Param("tenantId") Long tenantId, @Param("uid") Long uid);

    /** 某团队的在册成员（成员列表页）。 */
    @Select("""
            SELECT * FROM team_member
            WHERE tenant_id = #{tenantId} AND team_id = #{teamId} AND status = 1
            ORDER BY id ASC
            """)
    List<TeamMember> selectActiveByTeam(@Param("tenantId") Long tenantId, @Param("teamId") Long teamId);

    /** 本组织全部在册成员关系（一次性取回后在内存按团队分组，避免 N+1）。 */
    @Select("""
            SELECT * FROM team_member WHERE tenant_id = #{tenantId} AND status = 1
            ORDER BY team_id ASC, id ASC
            """)
    List<TeamMember> selectActiveByTenant(@Param("tenantId") Long tenantId);

    /** 某团队在册成员数（团队容量与"最后一个团队负责人不可移除"护栏）。 */
    @Select("""
            SELECT COUNT(*) FROM team_member
            WHERE tenant_id = #{tenantId} AND team_id = #{teamId} AND status = 1
            """)
    long countActiveByTeam(@Param("tenantId") Long tenantId, @Param("teamId") Long teamId);

    /** 某团队持有指定团队角色的在册成员数（{@code TEAM_OWNER} 唯一性护栏）。 */
    @Select("""
            SELECT COUNT(*) FROM team_member
            WHERE tenant_id = #{tenantId} AND team_id = #{teamId} AND team_role = #{teamRole} AND status = 1
            """)
    long countActiveByTeamRole(@Param("tenantId") Long tenantId,
                               @Param("teamId") Long teamId,
                               @Param("teamRole") String teamRole);

    /**
     * 精确取单条成员关系（团队角色变更/移除前的存在性与租户校验）。
     *
     * <p>不带 {@code status} 过滤：调用方需要区分"从未加入"（null）与"已移除"（status=0），
     * 后者应走"恢复在册"而非"重复插入"，否则会撞 {@code uk_team_uid} 唯一键。
     *
     * <p>方法名<b>不可</b>叫 {@code selectOne}：MyBatis 以"接口全名 + 方法名"注册 statement id，
     * 与 {@code BaseMapper.selectOne(Wrapper)} 同名会在启动时报映射冲突。
     */
    @Select("""
            SELECT * FROM team_member
            WHERE tenant_id = #{tenantId} AND team_id = #{teamId} AND uid = #{uid} LIMIT 1
            """)
    TeamMember selectMember(@Param("tenantId") Long tenantId,
                            @Param("teamId") Long teamId,
                            @Param("uid") Long uid);
}
