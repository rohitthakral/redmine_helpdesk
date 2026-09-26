require 'openssl'

# Signed, login-free download links for attachments sent to the supportclient.
# The token is bound to the attachment id and its digest, so it cannot be
# reused for other files and becomes invalid if the file is replaced.
module HelpdeskAttachmentLink
  def self.token_for(attachment)
    OpenSSL::HMAC.hexdigest(
      'SHA256',
      Rails.application.secret_key_base,
      "helpdesk-attachment-#{attachment.id}-#{attachment.digest}"
    )
  end

  def self.valid_token?(attachment, token)
    return false if attachment.nil? || token.blank?

    ActiveSupport::SecurityUtils.secure_compare(token_for(attachment), token.to_s)
  end
end
