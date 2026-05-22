class HelpdeskProjectDefault < ActiveRecord::Base
  belongs_to :project
  belongs_to :tracker, :optional => true
  belongs_to :default_assignee, :class_name => 'User', :optional => true

  validates :project, :presence => true
  validates :project_id, :uniqueness => true
  validate :tracker_belongs_to_project
  validate :default_assignee_is_assignable

  private

  def tracker_belongs_to_project
    return if tracker.blank? || project.blank? || project.trackers.include?(tracker)

    errors.add(:tracker_id, :inclusion)
  end

  def default_assignee_is_assignable
    return if default_assignee.blank? || project.blank?
    return if project.assignable_users.include?(default_assignee)

    errors.add(:default_assignee_id, :invalid)
  end
end
