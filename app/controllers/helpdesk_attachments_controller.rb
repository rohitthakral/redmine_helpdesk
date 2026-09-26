# Lets the supportclient download attachments from the helpdesk email
# without a Redmine account. Access is granted by the signed token only.
class HelpdeskAttachmentsController < ApplicationController
  skip_before_action :check_if_login_required, :check_password_change, :check_twofa_activation

  def download
    attachment = Attachment.find_by(:id => params[:id])
    if attachment.nil? ||
       !attachment.container.is_a?(Issue) ||
       !HelpdeskAttachmentLink.valid_token?(attachment, params[:token]) ||
       !attachment.readable?
      render_404
      return
    end

    attachment.increment_download
    send_file attachment.diskfile,
              :filename => filename_for_content_disposition(attachment.filename),
              :type => attachment.content_type.presence || 'application/octet-stream',
              :disposition => 'attachment'
  end
end
