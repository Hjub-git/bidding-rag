-- =====================================================================
-- 演示数据初始化脚本
-- 前置：先执行 schema.sql
-- 登录账号：
--   管理员      admin    / admin123
--   厂家演示    mfg01    / 123456  （mfg02、mfg03 同密码）
--   招标单位    tender01 / 123456  （tender02 同密码）
-- 说明：密码均为 bcrypt 加密存储，绝无明文。
-- =====================================================================

USE bidding_system;

-- ---------------------------------------------------------------------
-- 用户（显式指定 id，便于 seed 里用外键关联）
-- ---------------------------------------------------------------------
INSERT INTO users (id, username, password, email, role) VALUES
  (1, 'admin',    '$2b$10$r3JK1cUXhT5H/wsKr0NVEeCUxYgUPFJJ6r0eTwOb9J9E/VGOTkpV2', 'admin@example.com',    'admin'),
  (2, 'mfg01',    '$2b$10$30c/I3nqcFhmyQdU7NWAAOBpeWbOzscO4XSzKlL7FO3QCtMjXpo8C', 'mfg01@example.com',    'manufacturer'),
  (3, 'mfg02',    '$2b$10$30c/I3nqcFhmyQdU7NWAAOBpeWbOzscO4XSzKlL7FO3QCtMjXpo8C', 'mfg02@example.com',    'manufacturer'),
  (4, 'mfg03',    '$2b$10$30c/I3nqcFhmyQdU7NWAAOBpeWbOzscO4XSzKlL7FO3QCtMjXpo8C', 'mfg03@example.com',    'manufacturer'),
  (5, 'tender01', '$2b$10$30c/I3nqcFhmyQdU7NWAAOBpeWbOzscO4XSzKlL7FO3QCtMjXpo8C', 'tender01@example.com', 'tender'),
  (6, 'tender02', '$2b$10$30c/I3nqcFhmyQdU7NWAAOBpeWbOzscO4XSzKlL7FO3QCtMjXpo8C', 'tender02@example.com', 'tender');

-- ---------------------------------------------------------------------
-- 厂家档案
-- ---------------------------------------------------------------------
INSERT INTO manufacturers (id, user_id, company_name, legal_person, contact_phone, address, business_license, company_profile, status) VALUES
  (1, 2, '华东机电有限公司', '张三', '13800000001', '上海市浦东新区1号', 'LIC-MFG01', '专注机电生产20年',   'active'),
  (2, 3, '西北装备股份',   '李四', '13800000002', '西安市高新区2号',   'LIC-MFG02', '军工配套供应商',       'active'),
  (3, 4, '中原机电制造',   '王五', '13800000003', '郑州市金水区3号',   'LIC-MFG03', '中大型机电设备制造',   'active');

-- ---------------------------------------------------------------------
-- 招标单位档案
-- ---------------------------------------------------------------------
INSERT INTO tender_units (id, user_id, company_name, legal_person, contact_phone, address, business_license, company_profile, status) VALUES
  (1, 5, '华夏能源招标中心', '赵六', '13900000001', '北京市朝阳区1号', 'LIC-TU01', '能源行业招标',   'active'),
  (2, 6, '南方轨道招标平台', '钱七', '13900000002', '广州市天河区2号', 'LIC-TU02', '轨道交通采购',   'active');

-- ---------------------------------------------------------------------
-- 招标项目（截止日期为演示用的未来日期，多数为 open，少量 closed）
-- ---------------------------------------------------------------------
INSERT INTO tender_projects
  (id, project_number, tender_unit_id, equipment_name, model, packaging_requirements, quantity, deadline, deposit, description, status, deleted) VALUES
  (1,  'TP-2026-001', 1, '高压电机',    'HV-500',  '防潮防震',     10,  '2026-11-30', 50000.00,  '用于能源站改造',       'open', 0),
  (2,  'TP-2026-002', 1, '变压器',      'TR-1000', '木箱加防震',   5,   '2026-12-31', 80000.00,  '大型变压器采购',       'open', 0),
  (3,  'TP-2026-003', 2, '牵引电机',    'TM-300',  '金属箱包装',   20,  '2027-01-31', 120000.00, '地铁车辆用',           'open', 0),
  (4,  'TP-2026-004', 2, '控制柜',      'CC-42U',  '泡沫加木箱',   12,  '2027-02-28', 30000.00,  '车站配电系统',         'open', 0),
  (5,  'TP-2026-005', 1, '冷却泵',      'CP-80',   '加固木托',     15,  '2027-03-31', 20000.00,  '机房冷却系统改造',     'open', 0),
  (6,  'TP-2026-006', 1, '低压断路器',  'CB-125',  '纸箱+泡沫',    50,  '2027-04-30', 10000.00,  '配电房改造',           'open', 0),
  (7,  'TP-2026-007', 2, '车站UPS电源', 'UPS-20K', '木箱',         6,   '2026-09-30', 60000.00,  '轨道交通站房配套',     'closed', 0),
  (8,  'TP-2026-008', 1, 'PLC控制器',   'PLC-400', '防静电袋+纸箱',40,  '2027-05-31', 15000.00,  '自动化改造',           'open', 0),
  (9,  'TP-2026-009', 2, '牵引变流器',  'CONV-750','木箱+防震',    8,   '2026-08-31', 200000.00, '地铁牵引系统',         'closed', 0),
  (10, 'TP-2026-010', 1, '隔离变压器',  'ISO-800', '木托+缠绕膜',  10,  '2027-06-30', 50000.00,  '设备隔离供电',         'open', 0),
  (11, 'TP-2026-011', 2, '风机盘管',    'FCU-18',  '纸箱',         60,  '2026-07-31', 30000.00,  '站房暖通设备',         'closed', 0),
  (12, 'TP-2026-012', 1, '电缆桥架',    'CB-600',  '打包带捆扎',   100, '2027-07-31', 12000.00,  '综合管线改造',         'open', 0);

-- ---------------------------------------------------------------------
-- 留言 + 回复（演示公开留言）
-- ---------------------------------------------------------------------
INSERT INTO messages (id, user_id, tender_project_id, title, content, is_public, deleted) VALUES
  (1, 2, 1, '关于高压电机技术参数咨询', '请问HV-500是否支持变频控制？',         1, 0),
  (2, 3, 2, '变压器运输方案讨论',       'TR-1000运输需要注意哪些事项？',       1, 0),
  (3, 4, 3, '牵引电机供货周期',         'TM-300供货周期一般多久？',            1, 0);

INSERT INTO replies (id, message_id, user_id, content, deleted) VALUES
  (1, 1, 5, '支持变频，详见招标文件第3章技术参数。', 0),
  (2, 2, 5, '需加固外包装并购买全程保险。',         0),
  (3, 3, 6, '常规为45天，库存件可加急。',           0);
