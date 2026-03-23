-- Safe schema for KingdomsBounty (ChiselMod + ChiselRanks)
-- This version does NOT delete existing data.
-- It only creates tables if they do not already exist.

CREATE TABLE IF NOT EXISTS players (
  player_uuid VARCHAR(36) PRIMARY KEY,
  player_name VARCHAR(64) NOT NULL,
  last_seen BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_bounties (
  player_uuid VARCHAR(36) PRIMARY KEY,
  bounty INT DEFAULT 0,
  kills INT DEFAULT 0,
  deaths INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kingdom_bounties (
  kingdom_name VARCHAR(64) PRIMARY KEY,
  bounty INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS placed_bounties (
  id INT PRIMARY KEY AUTO_INCREMENT,
  target_type VARCHAR(16),
  target_name VARCHAR(64),
  placed_by VARCHAR(64),
  amount INT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kill_cooldowns (
  killer_uuid VARCHAR(36),
  victim_uuid VARCHAR(36),
  timestamp BIGINT,
  PRIMARY KEY (killer_uuid, victim_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wars (
  kingdom_a VARCHAR(64) NOT NULL,
  kingdom_b VARCHAR(64) NOT NULL,
  start_time BIGINT NOT NULL,
  active INT NOT NULL DEFAULT 0,
  PRIMARY KEY (kingdom_a, kingdom_b)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS war_kills (
  id INT PRIMARY KEY AUTO_INCREMENT,
  killer_uuid VARCHAR(36) NOT NULL,
  victim_uuid VARCHAR(36) NOT NULL,
  kingdom_killer VARCHAR(64) NOT NULL,
  kingdom_victim VARCHAR(64) NOT NULL,
  timestamp BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS assassination_contracts (
  id INT PRIMARY KEY AUTO_INCREMENT,
  target_uuid VARCHAR(36) NOT NULL,
  placed_by VARCHAR(64) NOT NULL,
  amount INT NOT NULL,
  timestamp BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS assassination_hits (
  id INT PRIMARY KEY AUTO_INCREMENT,
  requester_uuid VARCHAR(36) NOT NULL,
  requester_name VARCHAR(64) NOT NULL,
  target_uuid VARCHAR(36) NOT NULL UNIQUE,
  target_name VARCHAR(64) NOT NULL,
  amount INT NOT NULL,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  accepted_by_uuid VARCHAR(36) NULL,
  accepted_by_name VARCHAR(64) NULL,
  accepted_at BIGINT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kingdoms (
  name VARCHAR(64) PRIMARY KEY,
  leader VARCHAR(36) NOT NULL,
  color VARCHAR(32) NOT NULL,
  created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kingdom_members (
  player_uuid VARCHAR(36) PRIMARY KEY,
  kingdom VARCHAR(64) NOT NULL,
  CONSTRAINT fk_kingdom_members_kingdom
    FOREIGN KEY (kingdom) REFERENCES kingdoms(name)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_ranks (
  uuid VARCHAR(36) PRIMARY KEY,
  rank VARCHAR(20),
  purchase_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_trails (
  uuid VARCHAR(36) PRIMARY KEY,
  enabled BOOLEAN DEFAULT FALSE,
  trail_type VARCHAR(20) DEFAULT 'none'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chisel_lives_players (
  uuid VARCHAR(36) PRIMARY KEY,
  username VARCHAR(16),
  lives INT DEFAULT 10,
  banned BOOLEAN DEFAULT FALSE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chisel_lives_purchases (
  id INT AUTO_INCREMENT PRIMARY KEY,
  uuid VARCHAR(36),
  type VARCHAR(20),
  status VARCHAR(20) DEFAULT 'pending',
  purchase_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Job queue written by the PHP website and polled by the plugin every 5s.
CREATE TABLE IF NOT EXISTS website_purchase_jobs (
  id INT AUTO_INCREMENT PRIMARY KEY,
  uuid VARCHAR(36) NOT NULL,
  username VARCHAR(16),
  product VARCHAR(32) NOT NULL,
  status VARCHAR(16) DEFAULT 'pending',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  processed_at TIMESTAMP NULL,
  error_message VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Optional website/store table; plugin code does not require it directly.
CREATE TABLE IF NOT EXISTS purchases (
  id INT AUTO_INCREMENT PRIMARY KEY,
  uuid VARCHAR(36),
  type VARCHAR(20),
  rank VARCHAR(20),
  status VARCHAR(20) DEFAULT 'pending',
  purchase_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
