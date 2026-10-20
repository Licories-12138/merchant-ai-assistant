-- ============================================================
-- 知识库问答页面的菜单
--
-- 为什么要这个脚本：
-- 若依的前端路由不是写死在代码里的，是启动时调 /getRouters 按 sys_menu 表动态生成的。
-- 所以光把 .vue 放进 src/views 是访问不到的，必须在库里加菜单记录。
--
-- 执行方式：在 DataGrip 里连 ry-vue 库，整段执行。
-- 执行完要重新登录（或刷新页面），前端才会重新拉一次路由。
--
-- 字段说明（只解释容易填错的几个）：
--   menu_type   M=目录（只是个分组，点不开）  C=菜单（能点开一个页面）  F=按钮
--   component   相对 src/views 的路径，不带 .vue 后缀；目录填 null
--   route_name  要和页面里 <script setup name="Rag"> 的 name 一致，否则页面缓存（keep-alive）不生效
--   is_frame    1=不是外链   is_cache 0=缓存
--   visible/status 都是 '0' 表示显示/正常
-- ============================================================

-- 1) 一级目录：智能问答
insert into sys_menu (menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
values ('智能问答', 0, 4, 'ai', null, '', '',
        1, 0, 'M', '0', '0', '', 'guide',
        'admin', now(), '', null, '智能问答模块目录');

-- 2) 菜单：知识库问答，挂在上面那个目录下
--    last_insert_id() 取的就是第 1 条刚插进去的 menu_id，所以两条必须一起执行
insert into sys_menu (menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
values ('知识库问答', last_insert_id(), 1, 'rag', 'merchant/rag/index', '', 'Rag',
        1, 0, 'C', '0', '0', 'merchant:rag:list', 'education',
        'admin', now(), '', null, 'RAG 知识库：上传文档 + 提问 + 引用');

-- 3) 查一下结果，两条都应该在
select menu_id, menu_name, parent_id, path, component, route_name, menu_type, icon
from sys_menu
where menu_name in ('智能问答', '知识库问答')
order by menu_id;
