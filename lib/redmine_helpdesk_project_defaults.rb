module RedmineHelpdeskProjectDefaults
  module_function

  def find_or_initialize(project)
    HelpdeskProjectDefault.find_or_initialize_by(:project_id => project.id)
  end

  def default_tracker(project)
    defaults = find(project)
    return unless defaults

    defaults.tracker if defaults.tracker && project.trackers.include?(defaults.tracker)
  end

  def default_assignee(project)
    defaults = find(project)
    return unless defaults

    assignee = defaults.default_assignee
    assignee if assignee && project.assignable_users.include?(assignee)
  end

  def update(project, attributes)
    defaults = find_or_initialize(project)
    defaults.tracker_id = attributes[:tracker_id].presence
    defaults.default_assignee_id = attributes[:default_assignee_id].presence
    defaults.save
  end

  def find(project)
    return unless project

    HelpdeskProjectDefault.find_by(:project_id => project.id)
  end
end
