class CreateCustomFieldForDefaultTracker < ActiveRecord::Migration[5.2]
  def self.up

    unless table_exists?(:helpdesk_project_defaults)
      create_table :helpdesk_project_defaults do |t|
        t.integer :project_id, :null => false
        t.integer :tracker_id
        t.integer :default_assignee_id
        t.timestamps
      end
    end

    add_index :helpdesk_project_defaults, :project_id, :unique => true unless index_exists?(:helpdesk_project_defaults, :project_id)
    add_index :helpdesk_project_defaults, :tracker_id unless index_exists?(:helpdesk_project_defaults, :tracker_id)
    add_index :helpdesk_project_defaults, :default_assignee_id unless index_exists?(:helpdesk_project_defaults, :default_assignee_id)

    add_foreign_key :helpdesk_project_defaults, :projects unless foreign_key_exists?(:helpdesk_project_defaults, :projects)
    add_foreign_key :helpdesk_project_defaults, :trackers unless foreign_key_exists?(:helpdesk_project_defaults, :trackers)
    unless foreign_key_exists?(:helpdesk_project_defaults, :users, :column => :default_assignee_id)
      add_foreign_key :helpdesk_project_defaults, :users, :column => :default_assignee_id
    end
  end

  def self.down
    drop_table :helpdesk_project_defaults if table_exists?(:helpdesk_project_defaults)
  end
end
