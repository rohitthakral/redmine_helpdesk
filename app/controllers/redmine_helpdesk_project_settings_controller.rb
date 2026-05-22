class RedmineHelpdeskProjectSettingsController < ApplicationController
  before_action :find_project
  before_action :require_edit_project_permission

  helper :projects

  def update
    defaults_saved = RedmineHelpdeskProjectDefaults.update(
      @project,
      :tracker_id => params.dig(:helpdesk_defaults, :tracker_id),
      :default_assignee_id => params.dig(:helpdesk_defaults, :default_assignee_id)
    )

    if defaults_saved
      flash[:notice] = l(:notice_successful_update)
      redirect_to settings_project_path(@project, :tab => 'helpdesk_defaults')
    else
      @issue_custom_fields = IssueCustomField.sorted.to_a
      @issue_category ||= IssueCategory.new
      @member ||= @project.members.new
      @trackers = Tracker.sorted.to_a
      @version_status = params[:version_status] || 'open'
      @version_name = params[:version_name]
      @versions = @project.shared_versions.status(@version_status).like(@version_name).sorted
      render 'projects/settings'
    end
  end

  private

  def require_edit_project_permission
    deny_access unless User.current.allowed_to?(:edit_project, @project)
  end
end
